package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 指标取证明细（V15，只追加）：每项指标逐批次/逐异常一行，
 * 给出 PASS/FAIL/UNEVALUABLE 判定与依据文本；无法取证单独计数、不进分母。
 */
@TableName("t_cc_score_evidence")
public class ScoreEvidence extends CreateOnlyEntity {
    private Long snapshotId;
    private Long periodId;
    private Long carrierId;
    /** ON_TIME / TEMP_CONTROL / SIGN_COMPLETE / EXCEPTION_RESPONSE */
    private String metricType;
    private Long batchId;
    private Long exceptionId;
    private Long receiptId;
    /** PASS / FAIL / UNEVALUABLE */
    private String result;
    /** 判定依据（承诺vs签收时刻、异常发现vs确认、回执要素清单等） */
    private String basis;

    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getExceptionId() { return exceptionId; }
    public void setExceptionId(Long exceptionId) { this.exceptionId = exceptionId; }
    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getBasis() { return basis; }
    public void setBasis(String basis) { this.basis = basis; }
}
