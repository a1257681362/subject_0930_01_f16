package com.evops.coldchain;

import com.evops.coldchain.entity.RegReport;
import com.evops.coldchain.job.RegReportMonthlyJob;
import com.evops.coldchain.service.RegReportGenerateService;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V16 生产 Quartz 装配验证：在 coldchain.reg-report.enabled=true（默认）下，
 * 月度 JobDetail/CronTrigger 被正确注册（cron 默认每月 3 日 02:17），且 Job 经自定义
 * JobFactory 完成 Spring 注入后可端到端生成报表。与业务套件（关闭定时）互补，
 * 专门兜住“开关打开时 Quartz 能真正跑起来”这一生产关键路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "coldchain.reg-report.enabled=true")
@ActiveProfiles("test")
class RegReportQuartzWiringTest {

    @Autowired private Scheduler scheduler;
    @Autowired private RegReportGenerateService generateService;

    @Test
    void monthlyJobAndCronTriggerRegistered_andJobExecutes() throws Exception {
        // 1) Trigger 已注册且为月度 cron（每月 3 日 02:17）
        Trigger trigger = scheduler.getTrigger(new TriggerKey("regReportMonthlyTrigger", "coldchain"));
        assertNotNull(trigger, "月度报表 CronTrigger 应已注册");
        assertTrue(trigger instanceof CronTrigger);
        assertEquals("0 17 2 3 * ?", ((CronTrigger) trigger).getCronExpression());

        // 2) JobDetail 指向 RegReportMonthlyJob
        JobDetail detail = scheduler.getJobDetail(trigger.getJobKey());
        assertNotNull(detail);
        assertEquals(RegReportMonthlyJob.class, detail.getJobClass());

        // 3) 用 JobDataMap 覆盖目标月立即触发，验证自定义 JobFactory 注入 + 端到端生成（定时来源）
        String month = "2026-09";
        scheduler.triggerJob(detail.getKey(), monthData(month));
        RegReport report = await(month);
        assertNotNull(report);
        assertEquals(1, report.getVersionNo());
        assertEquals(CcConstants.TRIGGER_SCHEDULED, report.getTriggerSource());

        // 4) 再次触发仍幂等（只有一版）
        scheduler.triggerJob(detail.getKey(), monthData(month));
        Thread.sleep(600);
        assertEquals(1, generateService.list(month).size(), "Quartz 重复触发必须幂等不覆盖");
    }

    private org.quartz.JobDataMap monthData(String month) {
        org.quartz.JobDataMap data = new org.quartz.JobDataMap();
        data.put(RegReportMonthlyJob.JOB_DATA_MONTH, month);
        return data;
    }

    private RegReport await(String month) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            RegReport r = generateService.latestVersion(month);
            if (r != null) {
                return r;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待 Quartz 月度 Job 生成 " + month + " 报表超时");
    }
}
