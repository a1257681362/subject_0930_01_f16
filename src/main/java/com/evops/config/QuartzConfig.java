package com.evops.config;

import com.evops.coldchain.job.RegReportMonthlyJob;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

/**
 * Quartz 配置（V16）：让 Quartz 创建的 Job 实例具备 Spring 依赖注入能力，并注册
 * 冷链运输监管报表月度生成任务（默认每月 3 日 02:17 为上月出首版，数据已结账稳定）。
 *
 * 开关 coldchain.reg-report.enabled=false 时不注册 Job/Trigger（集成测试直调服务，避免定时干扰）；
 * cron 由 coldchain.reg-report.cron 覆盖（Quartz 6 段：秒 分 时 日 月 周）。
 */
@Configuration
public class QuartzConfig {

    /** 让 Quartz Job 可 @Resource 注入 Spring Bean（默认 JobFactory 不走 Spring 容器） */
    @Bean
    public AutowiringSpringBeanJobFactory autowiringSpringBeanJobFactory(ApplicationContext applicationContext) {
        AutowiringSpringBeanJobFactory factory = new AutowiringSpringBeanJobFactory();
        factory.setApplicationContext(applicationContext);
        return factory;
    }

    @Bean
    public SchedulerFactoryBeanCustomizer quartzSchedulerCustomizer(AutowiringSpringBeanJobFactory jobFactory) {
        return schedulerFactoryBean -> schedulerFactoryBean.setJobFactory(jobFactory);
    }

    @Bean
    @ConditionalOnProperty(name = "coldchain.reg-report.enabled", havingValue = "true", matchIfMissing = true)
    public JobDetail regReportMonthlyJobDetail() {
        JobDetailFactoryBean factory = new JobDetailFactoryBean();
        factory.setJobClass(RegReportMonthlyJob.class);
        factory.setName("regReportMonthlyJob");
        factory.setGroup("coldchain");
        factory.setDurability(true);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Bean
    @ConditionalOnProperty(name = "coldchain.reg-report.enabled", havingValue = "true", matchIfMissing = true)
    public Trigger regReportMonthlyTrigger(JobDetail regReportMonthlyJobDetail,
                                           org.springframework.core.env.Environment env) {
        String cron = env.getProperty("coldchain.reg-report.cron", "0 17 2 3 * ?");
        CronTriggerFactoryBean factory = new CronTriggerFactoryBean();
        factory.setJobDetail(regReportMonthlyJobDetail);
        factory.setName("regReportMonthlyTrigger");
        factory.setGroup("coldchain");
        try {
            factory.setCronExpression(cron);
            factory.afterPropertiesSet();
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException("监管报表 Quartz cron 表达式非法: " + cron, e);
        }
        return factory.getObject();
    }

    /** SpringBeanJobFactory + ApplicationContext 注入：Job 由 Quartz 实例化后按 Spring 自动装配依赖 */
    static class AutowiringSpringBeanJobFactory extends SpringBeanJobFactory implements ApplicationContextAware {
        private transient ApplicationContext applicationContext;

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        @Override
        protected Object createJobInstance(org.quartz.spi.TriggerFiredBundle bundle) throws Exception {
            Object job = super.createJobInstance(bundle);
            applicationContext.getAutowireCapableBeanFactory().autowireBean(job);
            return job;
        }
    }
}
