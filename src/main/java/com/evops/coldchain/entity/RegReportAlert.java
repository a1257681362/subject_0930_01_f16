package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 监管报表生成失败告警流水（V16，只追加）：每次失败告警尝试一行。
 * 复用 V6 通知通道（站内 / webhook）；FAILED 必填 fail_reason；历史行原样保留，不覆盖。
 */
@TableName("t_cc_reg_report_alert")
public class RegReportAlert extends CreateOnlyEntity {
    private Long runId;
    private Long reportId;
    private String reportMonth;
    /** SITE / WEBHOOK */
    private String channel;
    private Integer attemptNo;
    /** SUCCESS / FAILED */
    private String sendStatus;
    private String failReason;
    private LocalDateTime alertTime;

    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getReportMonth() { return reportMonth; }
    public void setReportMonth(String reportMonth) { this.reportMonth = reportMonth; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String sendStatus) { this.sendStatus = sendStatus; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public LocalDateTime getAlertTime() { return alertTime; }
    public void setAlertTime(LocalDateTime alertTime) { this.alertTime = alertTime; }
}
