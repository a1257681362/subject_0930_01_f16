package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 监管报表生成错误链（V16，只追加）：每次生成失败落一行，固化失败阶段/类型/消息/堆栈
 * 与告警投递结果。报表行此时尚未产生，错误链是唯一可追的失败事实，供运维与监管复查。
 *
 * alertStatus：SITE 无 webhook 时站内留痕告警 / SUCCESS webhook 投递成功 / FAILED webhook 投递失败。
 */
@TableName("t_cc_reg_report_error")
public class RegReportError extends CreateOnlyEntity {
    /** 目标统计月 yyyy-MM（失败时报表尚未生成） */
    private String statMonth;
    private Long caliberId;
    private Integer caliberVersionNo;
    /** SCHEDULED / MANUAL */
    private String triggerSource;
    /** LOAD_CALIBER / COMPUTE / PERSIST / ALERT */
    private String failStage;
    private String errorType;
    private String errorMessage;
    private String stackTrace;
    /** SITE / SUCCESS / FAILED */
    private String alertStatus;
    private String alertTarget;
    private String alertFailReason;
    private String operator;

    public String getStatMonth() { return statMonth; }
    public void setStatMonth(String statMonth) { this.statMonth = statMonth; }
    public Long getCaliberId() { return caliberId; }
    public void setCaliberId(Long caliberId) { this.caliberId = caliberId; }
    public Integer getCaliberVersionNo() { return caliberVersionNo; }
    public void setCaliberVersionNo(Integer caliberVersionNo) { this.caliberVersionNo = caliberVersionNo; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public String getFailStage() { return failStage; }
    public void setFailStage(String failStage) { this.failStage = failStage; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }
    public String getAlertStatus() { return alertStatus; }
    public void setAlertStatus(String alertStatus) { this.alertStatus = alertStatus; }
    public String getAlertTarget() { return alertTarget; }
    public void setAlertTarget(String alertTarget) { this.alertTarget = alertTarget; }
    public String getAlertFailReason() { return alertFailReason; }
    public void setAlertFailReason(String alertFailReason) { this.alertFailReason = alertFailReason; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
