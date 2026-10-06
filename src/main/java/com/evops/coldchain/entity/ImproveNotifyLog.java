package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 改进任务逾期提醒流水（V15，只追加）：每次尝试独立一行，
 * 失败必填 failReason，重试必填 retryReason；历史失败行原样保留。
 */
@TableName("t_cc_improve_notify_log")
public class ImproveNotifyLog extends CreateOnlyEntity {
    private Long taskId;
    private String ownerName;
    /** SITE / WEBHOOK */
    private String channel;
    private Integer attemptNo;
    /** SUCCESS / FAILED */
    private String sendStatus;
    private String failReason;
    private String retryReason;
    private LocalDateTime sendTime;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String sendStatus) { this.sendStatus = sendStatus; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getRetryReason() { return retryReason; }
    public void setRetryReason(String retryReason) { this.retryReason = retryReason; }
    public LocalDateTime getSendTime() { return sendTime; }
    public void setSendTime(LocalDateTime sendTime) { this.sendTime = sendTime; }
}
