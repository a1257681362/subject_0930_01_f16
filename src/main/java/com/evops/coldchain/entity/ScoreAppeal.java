package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 承运商申诉（V15）：只针对本承运商快照、申诉窗口内提交。
 * 申诉只写本表与复核表，原评分快照任何字段不回写。
 */
@TableName("t_cc_score_appeal")
public class ScoreAppeal extends BaseEntity {
    private String appealNo;
    private Long snapshotId;
    private Long periodId;
    private Long carrierId;
    /** 申诉针对的指标类型 */
    private String metricType;
    private String reason;
    /** 申诉人随附证据说明 */
    private String evidenceRefs;
    /** PENDING 待复核 / UPHELD 申诉成立 / REJECTED 申诉驳回 */
    private String status;
    private Long submitBy;
    private String submitByName;
    private LocalDateTime submitTime;

    public String getAppealNo() { return appealNo; }
    public void setAppealNo(String appealNo) { this.appealNo = appealNo; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getEvidenceRefs() { return evidenceRefs; }
    public void setEvidenceRefs(String evidenceRefs) { this.evidenceRefs = evidenceRefs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getSubmitBy() { return submitBy; }
    public void setSubmitBy(Long submitBy) { this.submitBy = submitBy; }
    public String getSubmitByName() { return submitByName; }
    public void setSubmitByName(String submitByName) { this.submitByName = submitByName; }
    public LocalDateTime getSubmitTime() { return submitTime; }
    public void setSubmitTime(LocalDateTime submitTime) { this.submitTime = submitTime; }
}
