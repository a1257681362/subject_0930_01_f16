package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 监管报表生成尝试记录（V16，只追加）：每次生成（含定时/手动、成功/失败）各一行。
 * 失败也保留：attempt_no 递增，记录错误类型、message、完整 cause 链路与堆栈，
 * 与 {@link RegReportAlert} 告警流水共同构成可追溯的错误链路。
 */
@TableName("t_cc_reg_report_run")
public class RegReportRun extends CreateOnlyEntity {
    private Long reportId;
    private String reportMonth;
    private String superviseRegion;
    private Integer attemptNo;
    /** SCHEDULED / MANUAL */
    private String triggerSource;
    private Integer caliberVersionNo;
    /** SUCCESS / FAILED */
    private String runStatus;
    private Long versionId;
    private String errorType;
    private String errorMessage;
    /** 完整 cause 链路（逐环 异常类型:message） */
    private String errorChain;
    /** 完整堆栈 */
    private String errorStack;
    private LocalDateTime runTime;

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getReportMonth() { return reportMonth; }
    public void setReportMonth(String reportMonth) { this.reportMonth = reportMonth; }
    public String getSuperviseRegion() { return superviseRegion; }
    public void setSuperviseRegion(String superviseRegion) { this.superviseRegion = superviseRegion; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public Integer getCaliberVersionNo() { return caliberVersionNo; }
    public void setCaliberVersionNo(Integer caliberVersionNo) { this.caliberVersionNo = caliberVersionNo; }
    public String getRunStatus() { return runStatus; }
    public void setRunStatus(String runStatus) { this.runStatus = runStatus; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getErrorChain() { return errorChain; }
    public void setErrorChain(String errorChain) { this.errorChain = errorChain; }
    public String getErrorStack() { return errorStack; }
    public void setErrorStack(String errorStack) { this.errorStack = errorStack; }
    public LocalDateTime getRunTime() { return runTime; }
    public void setRunTime(LocalDateTime runTime) { this.runTime = runTime; }
}
