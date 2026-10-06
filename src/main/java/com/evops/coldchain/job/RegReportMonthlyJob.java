package com.evops.coldchain.job;

import com.evops.coldchain.service.RegReportGenerateService;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.quartz.QuartzJobBean;

import javax.annotation.Resource;
import java.time.YearMonth;

/**
 * 冷链运输监管报表月度生成任务（V16，Quartz 托管）。
 *
 * 每次触发为“刚结束的上一个自然月”生成首版报表（幂等：该月已有任意版本则跳过，
 * 不重复、不覆盖）；人工重算走独立接口，只追加新版本。
 *
 * JobDataMap 可传 {@code statMonth=yyyy-MM} 覆盖目标月（供运维补跑/测试）；
 * 生成失败已由服务落错误链并告警，此处仅记录日志、不向 Quartz 抛异常，单次失败不影响下次调度。
 * Job 实例由 {@link AutowiringSpringBeanJobFactory} 创建并注入 Spring 依赖（字段注入）。
 */
public class RegReportMonthlyJob extends QuartzJobBean {

    public static final String JOB_DATA_MONTH = "statMonth";
    private static final Logger log = LoggerFactory.getLogger(RegReportMonthlyJob.class);

    @Resource
    private RegReportGenerateService generateService;

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        String override = context.getMergedJobDataMap().getString(JOB_DATA_MONTH);
        YearMonth target = (override == null || override.trim().isEmpty())
                ? YearMonth.now().minusMonths(1)
                : YearMonth.parse(override.trim());
        try {
            RegReportGenerateService.GenerateOutcome outcome = generateService.generateScheduled(target);
            if (outcome.skipped) {
                // 幂等跳过：该月已有版本（含待补），不重复生成、不覆盖
                log.info("监管报表定时生成跳过：{} 已有版本 V{}（报表 {}），不重复生成",
                        target, outcome.report.getVersionNo(), outcome.report.getReportNo());
            } else {
                log.info("监管报表定时生成完成：{} 生成 {} V{}（状态={}）",
                        target, outcome.report.getReportNo(), outcome.report.getVersionNo(),
                        outcome.report.getStatus());
            }
        } catch (Exception e) {
            // 失败事实（错误链 + 告警）已在服务内独立事务保留；仅记录，避免 Quartz 误判任务中断
            log.error("监管报表定时生成失败：目标月 " + target + "（错误链与告警已保留，下个周期/人工可重算）", e);
        }
    }
}
