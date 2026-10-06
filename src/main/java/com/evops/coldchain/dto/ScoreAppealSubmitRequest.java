package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 承运商申诉（V15）：仅本承运商快照、申诉窗口内；申诉期间原分数不回写 */
public class ScoreAppealSubmitRequest {
    @NotNull(message = "评分快照ID不能为空")
    private Long snapshotId;
    @NotBlank(message = "申诉指标类型不能为空")
    private String metricType;
    @NotBlank(message = "申诉原因不能为空")
    private String reason;
    /** 申诉人随附证据说明 */
    private String evidenceRefs;

    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getEvidenceRefs() { return evidenceRefs; }
    public void setEvidenceRefs(String evidenceRefs) { this.evidenceRefs = evidenceRefs; }
}
