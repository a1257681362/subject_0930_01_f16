package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 回放批次差异明细（V14）：一个历史批次一行，记录基线/目标两版的基础运费、扣罚、应付，
 * diff_amount=目标应付-基线应付（正=新版本多付/少扣，负=少付/多扣），
 * diff_detail 为逐项费用对齐的文字解释（JSON）。frozen=TRUE 表示该批次已审核冻结，按冻结单回放。
 */
@TableName("t_cc_freight_replay_item")
public class FreightReplayItem extends CreateOnlyEntity {
    private Long replayId;
    private Long batchId;
    private String batchNo;
    private Long auditedSettlementId;
    private Boolean frozen;
    private BigDecimal baselineBaseAmount;
    private BigDecimal baselinePenaltyAmount;
    private BigDecimal baselinePayable;
    private BigDecimal targetBaseAmount;
    private BigDecimal targetPenaltyAmount;
    private BigDecimal targetPayable;
    private BigDecimal diffAmount;
    private String diffDetail;

    public Long getReplayId() { return replayId; }
    public void setReplayId(Long replayId) { this.replayId = replayId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public Long getAuditedSettlementId() { return auditedSettlementId; }
    public void setAuditedSettlementId(Long auditedSettlementId) { this.auditedSettlementId = auditedSettlementId; }
    public Boolean getFrozen() { return frozen; }
    public void setFrozen(Boolean frozen) { this.frozen = frozen; }
    public BigDecimal getBaselineBaseAmount() { return baselineBaseAmount; }
    public void setBaselineBaseAmount(BigDecimal baselineBaseAmount) { this.baselineBaseAmount = baselineBaseAmount; }
    public BigDecimal getBaselinePenaltyAmount() { return baselinePenaltyAmount; }
    public void setBaselinePenaltyAmount(BigDecimal baselinePenaltyAmount) { this.baselinePenaltyAmount = baselinePenaltyAmount; }
    public BigDecimal getBaselinePayable() { return baselinePayable; }
    public void setBaselinePayable(BigDecimal baselinePayable) { this.baselinePayable = baselinePayable; }
    public BigDecimal getTargetBaseAmount() { return targetBaseAmount; }
    public void setTargetBaseAmount(BigDecimal targetBaseAmount) { this.targetBaseAmount = targetBaseAmount; }
    public BigDecimal getTargetPenaltyAmount() { return targetPenaltyAmount; }
    public void setTargetPenaltyAmount(BigDecimal targetPenaltyAmount) { this.targetPenaltyAmount = targetPenaltyAmount; }
    public BigDecimal getTargetPayable() { return targetPayable; }
    public void setTargetPayable(BigDecimal targetPayable) { this.targetPayable = targetPayable; }
    public BigDecimal getDiffAmount() { return diffAmount; }
    public void setDiffAmount(BigDecimal diffAmount) { this.diffAmount = diffAmount; }
    public String getDiffDetail() { return diffDetail; }
    public void setDiffDetail(String diffDetail) { this.diffDetail = diffDetail; }
}
