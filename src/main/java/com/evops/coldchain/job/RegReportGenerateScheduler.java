package com.evops.coldchain.job;

import com.evops.coldchain.service.RegReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 冷链运输监管报表定时生成（V16）。
 *
 * 固定间隔触发，幂等地为“上一自然月”生成全域汇总报表：已有成功版本则跳过，
 * 尚未生成（或此前仅失败过）则按月口径生成快照。失败由服务记录运行记录与告警，
 * 单次失败不影响下次调度。
 *
 * 说明：项目统一使用 Spring {@code @Scheduled}（虽引入 quartz starter，但全库无 Quartz API），
 * 故沿用同一范式。定时线程无登录主体，操作者回退 system。
 * 通过 coldchain.reg-report.enabled=false 关闭（集成测试直调服务，避免定时器干扰）。
 */
@Component
@ConditionalOnProperty(name = "coldchain.reg-report.enabled", havingValue = "true", matchIfMissing = true)
public class RegReportGenerateScheduler {

    private static final Logger log = LoggerFactory.getLogger(RegReportGenerateScheduler.class);

    @Resource
    private RegReportService reportService;

    @Scheduled(fixedDelayString = "${coldchain.reg-report.fixed-delay-ms:3600000}",
            initialDelayString = "${coldchain.reg-report.initial-delay-ms:30000}")
    public void generateMonthly() {
        try {
            Map<String, Object> summary = reportService.scanMonthly(LocalDateTime.now());
            if (Boolean.TRUE.equals(summary.get("skipped"))) {
                log.debug("监管报表定时生成跳过（已存在版本）: {}", summary);
            } else {
                log.info("监管报表定时生成完成: {}", summary);
            }
        } catch (Exception e) {
            // 失败已在服务内记录运行记录/告警；此处兜底不影响下次调度
            log.error("监管报表定时生成扫描失败，将在下个周期重试", e);
        }
    }
}
