package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用预结算单（V14）。
 * 生成瞬间固化合同版本号与整版规则 JSON 快照（rule_snapshot）；费用明细逐行在
 * {@link FreightSettleItem}。状态机：PENDING（待审核）/ DISPUTED（有待复核争议项）/
 * AUDITED（财务审核冻结）。AUDITED 后金额与快照永久冻结，任何新合同版本/回放都不得重算。
 */
@TableName("t_cc_freight_settlement")
public class FreightSettlement extends BaseEntity {
    private String settlementNo;
    private Long batchId;
    private String batchNo;
    private String carrierNameSnap;
    private String boxTypeSnap;
    private String boxTypeNameSnap;
    private BigDecimal mileageKmSnap;
    /** ROUTE / MANUAL */
    private String mileageSource;
    private Long contractId;
    private Integer contractVersionNo;
    private String ruleSnapshot;
    private BigDecimal baseAmount;
    private BigDecimal penaltyAmount;
    private BigDecimal payableAmount;
    private String currency;
    /** PENDING / DISPUTED / AUDITED */
    private String status;
    private LocalDateTime generateTime;
    private Long generateBy;
    private String generateByName;
    private Integer disputePendingCount;
    private LocalDateTime auditTime;
    private Long auditBy;
    private String auditByName;
    private String auditRemark;

    public String getSettlementNo() { return settlementNo; }
    public void setSettlementNo(String settlementNo) { this.settlementNo = settlementNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getCarrierNameSnap() { return carrierNameSnap; }
    public void setCarrierNameSnap(String carrierNameSnap) { this.carrierNameSnap = carrierNameSnap; }
    public String getBoxTypeSnap() { return boxTypeSnap; }
    public void setBoxTypeSnap(String boxTypeSnap) { this.boxTypeSnap = boxTypeSnap; }
    public String getBoxTypeNameSnap() { return boxTypeNameSnap; }
    public void setBoxTypeNameSnap(String boxTypeNameSnap) { this.boxTypeNameSnap = boxTypeNameSnap; }
    public BigDecimal getMileageKmSnap() { return mileageKmSnap; }
    public void setMileageKmSnap(BigDecimal mileageKmSnap) { this.mileageKmSnap = mileageKmSnap; }
    public String getMileageSource() { return mileageSource; }
    public void setMileageSource(String mileageSource) { this.mileageSource = mileageSource; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public Integer getContractVersionNo() { return contractVersionNo; }
    public void setContractVersionNo(Integer contractVersionNo) { this.contractVersionNo = contractVersionNo; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
    public BigDecimal getBaseAmount() { return baseAmount; }
    public void setBaseAmount(BigDecimal baseAmount) { this.baseAmount = baseAmount; }
    public BigDecimal getPenaltyAmount() { return penaltyAmount; }
    public void setPenaltyAmount(BigDecimal penaltyAmount) { this.penaltyAmount = penaltyAmount; }
    public BigDecimal getPayableAmount() { return payableAmount; }
    public void setPayableAmount(BigDecimal payableAmount) { this.payableAmount = payableAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getGenerateTime() { return generateTime; }
    public void setGenerateTime(LocalDateTime generateTime) { this.generateTime = generateTime; }
    public Long getGenerateBy() { return generateBy; }
    public void setGenerateBy(Long generateBy) { this.generateBy = generateBy; }
    public String getGenerateByName() { return generateByName; }
    public void setGenerateByName(String generateByName) { this.generateByName = generateByName; }
    public Integer getDisputePendingCount() { return disputePendingCount; }
    public void setDisputePendingCount(Integer disputePendingCount) { this.disputePendingCount = disputePendingCount; }
    public LocalDateTime getAuditTime() { return auditTime; }
    public void setAuditTime(LocalDateTime auditTime) { this.auditTime = auditTime; }
    public Long getAuditBy() { return auditBy; }
    public void setAuditBy(Long auditBy) { this.auditBy = auditBy; }
    public String getAuditByName() { return auditByName; }
    public void setAuditByName(String auditByName) { this.auditByName = auditByName; }
    public String getAuditRemark() { return auditRemark; }
    public void setAuditRemark(String auditRemark) { this.auditRemark = auditRemark; }
}
