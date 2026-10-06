package com.evops.coldchain.job;

import com.evops.coldchain.service.ImproveTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 改进任务逾期提醒定时扫描（V15）。
 *
 * 固定间隔触发 {@link ImproveTaskService#scanOverdue}：
 * 逾期未完成任务自动提醒负责人；外部依赖失败只留失败流水（不假装成功），
 * 恢复后恰好一条成功通知（去重登记兜底，不重复）。
 * 定时线程无登录主体，操作者回退记为 system。
 * 通过 coldchain.score-remind.enabled=false 关闭（集成测试直调服务，避免定时器干扰）。
 */
@Component
@ConditionalOnProperty(name = "coldchain.score-remind.enabled", havingValue = "true", matchIfMissing = true)
public class ScoreTaskRemindScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScoreTaskRemindScheduler.class);

    @Resource
    private ImproveTaskService taskService;

    @Scheduled(fixedDelayString = "${coldchain.score-remind.fixed-delay-ms:60000}",
            initialDelayString = "${coldchain.score-remind.initial-delay-ms:25000}")
    public void scanOverdue() {
        try {
            Map<String, Object> summary = taskService.scanOverdue(LocalDateTime.now());
            int sent = (int) summary.getOrDefault("sent", 0);
            int failed = (int) summary.getOrDefault("failed", 0);
            if (sent > 0 || failed > 0 || log.isDebugEnabled()) {
                log.info("改进任务逾期提醒扫描完成: {}", summary);
            }
        } catch (Exception e) {
            // 单次失败不影响下次调度；提醒发送按当前状态幂等重算
            log.error("改进任务逾期提醒扫描失败，将在下个周期重试", e);
        }
    }
}
