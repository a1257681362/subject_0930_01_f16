package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ImproveNotifyLog;
import com.evops.coldchain.entity.ImproveRemindDedup;
import com.evops.coldchain.entity.ImproveTask;
import com.evops.coldchain.entity.ScoreAppeal;
import com.evops.coldchain.entity.ScorePeriod;
import com.evops.coldchain.entity.ScoreRule;
import com.evops.coldchain.mapper.ImproveNotifyLogMapper;
import com.evops.coldchain.mapper.ImproveRemindDedupMapper;
import com.evops.coldchain.mapper.ImproveTaskMapper;
import com.evops.coldchain.mapper.ScoreAppealMapper;
import com.evops.coldchain.mapper.ScorePeriodMapper;
import com.evops.coldchain.mapper.ScoreRuleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 改进任务与逾期提醒（V15）。
 *
 * - 任务 PENDING -> DONE 只进不退；
 * - 逾期自动提醒负责人：每次尝试写 t_cc_improve_notify_log（attempt_no 递增，失败记原因）；
 * - 外部依赖（webhook）无响应时只落 FAILED 流水，任务保持未提醒——不假装成功；
 * - 发送成功同事务写 t_cc_improve_remind_dedup（task_id 唯一）并置任务 SENT——
 *   依赖恢复后恰好一条成功通知，不重复。
 */
@Service
public class ImproveTaskService {

    @Resource
    private ImproveTaskMapper taskMapper;
    @Resource
    private ImproveNotifyLogMapper notifyMapper;
    @Resource
    private ImproveRemindDedupMapper dedupMapper;
    @Resource
    private ScoreAppealMapper appealMapper;
    @Resource
    private ScorePeriodMapper periodMapper;
    @Resource
    private ScoreRuleMapper ruleMapper;
    @Resource
    private ExceptionNotifyTransport transport;
    @Resource
    private OpLogService opLogService;

    /** 完成确认：PENDING -> DONE 只进不退 */
    @Transactional
    public ImproveTask done(Long taskId, String doneNote) {
        ImproveTask task = requireTask(taskId);
        if (CcConstants.TASK_DONE.equals(task.getStatus())) {
            throw new BusinessException("改进任务 " + task.getTaskNo() + " 已完成，完成事实不可改写");
        }
        task.setStatus(CcConstants.TASK_DONE);
        task.setDoneTime(LocalDateTime.now());
        task.setDoneBy(UserContext.currentId());
        task.setDoneByName(UserContext.currentName());
        task.setDoneNote(doneNote.trim());
        taskMapper.updateById(task);

        opLogService.log(CcConstants.ENT_IMPROVE_TASK, task.getId(), null, CcConstants.ACT_IMPROVE_TASK_DONE,
                String.format("改进任务 %s 完成：%s，完成说明=%s", task.getTaskNo(), task.getTitle(), doneNote.trim()));
        return task;
    }

    /**
     * 逾期提醒扫描（定时任务/手动入口同一逻辑）：
     * 对逾期未完成的 PENDING 任务发提醒；失败不置 SENT（下轮重试），成功恰好一次。
     * @return 扫描摘要（sent/failed/skipped）
     */
    @Transactional
    public Map<String, Object> scanOverdue(LocalDateTime now) {
        List<ImproveTask> candidates = taskMapper.selectList(new QueryWrapper<ImproveTask>()
                .eq("status", CcConstants.TASK_PENDING)
                .eq("remind_status", CcConstants.REMIND_NONE)
                .lt("due_time", now)
                .orderByAsc("id"));
        int sent = 0;
        int failed = 0;
        int skipped = 0;
        for (ImproveTask task : candidates) {
            // 去重兜底：已有成功提醒登记的任务跳过（恢复后不重复通知）
            Long done = dedupMapper.selectCount(new QueryWrapper<ImproveRemindDedup>()
                    .eq("task_id", task.getId()));
            if (done != null && done > 0) {
                skipped++;
                continue;
            }
            boolean ok = remind(task, "定时扫描：任务已逾期（截止 " + task.getDueTime() + "）未完成，自动提醒负责人");
            if (ok) {
                sent++;
            } else {
                failed++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scanned", candidates.size());
        summary.put("sent", sent);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        return summary;
    }

    /** 手动重试：必须填重试原因；已成功送达的拒绝无意义重试 */
    @Transactional
    public ImproveNotifyLog retry(Long taskId, String retryReason) {
        ImproveTask task = requireTask(taskId);
        if (retryReason == null || retryReason.trim().isEmpty()) {
            throw new BusinessException("重试提醒必须填写重试原因");
        }
        Long done = dedupMapper.selectCount(new QueryWrapper<ImproveRemindDedup>()
                .eq("task_id", task.getId()));
        if (done != null && done > 0) {
            throw new BusinessException("任务 " + task.getTaskNo() + " 的提醒已成功送达，无需重试");
        }
        remind(task, retryReason.trim());
        List<ImproveNotifyLog> logs = listLogs(taskId);
        ImproveNotifyLog last = logs.get(logs.size() - 1);

        opLogService.log(CcConstants.ENT_IMPROVE_TASK, task.getId(), null, CcConstants.ACT_IMPROVE_TASK_REMIND_RETRY,
                String.format("改进任务 %s 提醒手动重试：第%d次尝试，重试原因=%s，结果=%s%s",
                        task.getTaskNo(), last.getAttemptNo(), retryReason.trim(), last.getSendStatus(),
                        last.getFailReason() == null ? "" : "，失败原因=" + last.getFailReason()));
        return last;
    }

    /**
     * 发送一次提醒：成功则同事务登记去重并置任务 SENT；失败只落 FAILED 流水（不假装成功）。
     * @return 是否送达成功
     */
    private boolean remind(ImproveTask task, String reason) {
        int attempt = nextAttemptNo(task.getId());
        String endpoint = resolveEndpoint(task);
        ImproveNotifyLog row = new ImproveNotifyLog();
        row.setTaskId(task.getId());
        row.setOwnerName(task.getOwnerName());
        row.setAttemptNo(attempt);
        row.setSendTime(LocalDateTime.now());
        row.setRetryReason(reason);

        if (endpoint == null || endpoint.trim().isEmpty()) {
            // 站内提醒：演示环境视为必达（写库即送达）
            row.setChannel(CcConstants.NOTIFY_CHANNEL_SITE);
            row.setSendStatus(CcConstants.NOTIFY_SUCCESS);
        } else {
            row.setChannel(CcConstants.NOTIFY_CHANNEL_WEBHOOK);
            ExceptionNotifyTransport.SendResult result = transport.send(endpoint, buildPayload(task));
            row.setSendStatus(result.isSuccess() ? CcConstants.NOTIFY_SUCCESS : CcConstants.NOTIFY_FAILED);
            if (!result.isSuccess()) {
                row.setFailReason(result.getFailReason());
            }
        }
        notifyMapper.insert(row);

        if (CcConstants.NOTIFY_SUCCESS.equals(row.getSendStatus())) {
            // 成功同事务登记去重（task_id 唯一）：恢复后恰好一条成功通知，不重复
            ImproveRemindDedup dedup = new ImproveRemindDedup();
            dedup.setTaskId(task.getId());
            dedup.setNotifyLogId(row.getId());
            try {
                dedupMapper.insert(dedup);
            } catch (DuplicateKeyException e) {
                // 已有成功登记（并发/重入）：本次不再重复置态
                return true;
            }
            task.setRemindStatus(CcConstants.REMIND_SENT);
            task.setRemindTime(row.getSendTime());
            taskMapper.updateById(task);
        }

        opLogService.log(CcConstants.ENT_IMPROVE_TASK, task.getId(), null, CcConstants.ACT_IMPROVE_TASK_REMIND,
                String.format("改进任务 %s 逾期提醒：负责人=%s，第%d次尝试，渠道=%s，结果=%s%s",
                        task.getTaskNo(), task.getOwnerName(), attempt, row.getChannel(), row.getSendStatus(),
                        row.getFailReason() == null ? "" : "，失败原因=" + row.getFailReason()));
        return CcConstants.NOTIFY_SUCCESS.equals(row.getSendStatus());
    }

    /** 提醒投递地址：任务 -> 申诉 -> 周期 -> 绑定规则上的 webhook；空=站内必达 */
    private String resolveEndpoint(ImproveTask task) {
        ScoreAppeal appeal = appealMapper.selectById(task.getAppealId());
        if (appeal == null) {
            return null;
        }
        ScorePeriod period = periodMapper.selectById(appeal.getPeriodId());
        if (period == null) {
            return null;
        }
        ScoreRule rule = ruleMapper.selectById(period.getRuleId());
        return rule == null ? null : rule.getNotifyEndpoint();
    }

    private String buildPayload(ImproveTask task) {
        return String.format(
                "{\"taskNo\":\"%s\",\"ownerName\":\"%s\",\"title\":\"%s\",\"dueTime\":\"%s\",\"metricType\":\"%s\"}",
                task.getTaskNo(), task.getOwnerName(), task.getTitle(), task.getDueTime(), task.getMetricType());
    }

    private int nextAttemptNo(Long taskId) {
        Long n = notifyMapper.selectCount(new QueryWrapper<ImproveNotifyLog>().eq("task_id", taskId));
        return (n == null ? 0 : n.intValue()) + 1;
    }

    public List<ImproveNotifyLog> listLogs(Long taskId) {
        return notifyMapper.selectList(new QueryWrapper<ImproveNotifyLog>()
                .eq("task_id", taskId).orderByAsc("id"));
    }

    public List<ImproveTask> listTasks() {
        return taskMapper.selectList(new QueryWrapper<ImproveTask>().orderByDesc("id"));
    }

    /** 任务详情（含提醒流水） */
    public Map<String, Object> taskDetail(Long taskId) {
        ImproveTask task = requireTask(taskId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("task", task);
        view.put("notifyLogs", listLogs(taskId));
        return view;
    }

    public ImproveTask requireTask(Long taskId) {
        ImproveTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("改进任务不存在: " + taskId);
        }
        return task;
    }
}
