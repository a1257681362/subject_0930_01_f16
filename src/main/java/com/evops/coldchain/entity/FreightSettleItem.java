package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 结算费用项（V14）：基础运费一项 + 每条已关闭异常扣罚一项。
 * 每行同时保存业务依据（运营可见）与计价依据/单价（仅财务可见），
 * 命中规则逐字段快照在 rule_snapshot。争议与复核只追加，不覆盖原金额；
 * 复核“不计入”把 included 置 FALSE，重算应付时剔除本行，原金额仍保留可查。
 */
@TableName("t_cc_freight_settle_item")
public class FreightSettleItem extends BaseEntity {
    private Long settlementId;
    private Integer itemSeq;
    /** BASE / PENALTY */
    private String itemKind;
    private String title;
    private Long exceptionId;
    private String exceptionType;
    private String exceptionTypeName;
    private String gradeSnap;
    /** RATE / FIXED / PERCENT */
    private String calcType;
    private BigDecimal unitPrice;
    private BigDecimal quantity;
    private BigDecimal amount;
    private Boolean included;
    private String basisBusiness;
    private String basisPricing;
    private String ruleSnapshot;
    /** NONE / PENDING / INCLUDED / EXCLUDED */
    private String disputeStatus;
    private String disputeReason;
    private Long disputeBy;
    private String disputeByName;
    private LocalDateTime disputeTime;
    private Long reviewBy;
    private String reviewByName;
    private LocalDateTime reviewTime;
    private String reviewNote;

    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public Integer getItemSeq() { return itemSeq; }
    public void setItemSeq(Integer itemSeq) { this.itemSeq = itemSeq; }
    public String getItemKind() { return itemKind; }
    public void setItemKind(String itemKind) { this.itemKind = itemKind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Long getExceptionId() { return exceptionId; }
    public void setExceptionId(Long exceptionId) { this.exceptionId = exceptionId; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public String getExceptionTypeName() { return exceptionTypeName; }
    public void setExceptionTypeName(String exceptionTypeName) { this.exceptionTypeName = exceptionTypeName; }
    public String getGradeSnap() { return gradeSnap; }
    public void setGradeSnap(String gradeSnap) { this.gradeSnap = gradeSnap; }
    public String getCalcType() { return calcType; }
    public void setCalcType(String calcType) { this.calcType = calcType; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Boolean getIncluded() { return included; }
    public void setIncluded(Boolean included) { this.included = included; }
    public String getBasisBusiness() { return basisBusiness; }
    public void setBasisBusiness(String basisBusiness) { this.basisBusiness = basisBusiness; }
    public String getBasisPricing() { return basisPricing; }
    public void setBasisPricing(String basisPricing) { this.basisPricing = basisPricing; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
    public String getDisputeStatus() { return disputeStatus; }
    public void setDisputeStatus(String disputeStatus) { this.disputeStatus = disputeStatus; }
    public String getDisputeReason() { return disputeReason; }
    public void setDisputeReason(String disputeReason) { this.disputeReason = disputeReason; }
    public Long getDisputeBy() { return disputeBy; }
    public void setDisputeBy(Long disputeBy) { this.disputeBy = disputeBy; }
    public String getDisputeByName() { return disputeByName; }
    public void setDisputeByName(String disputeByName) { this.disputeByName = disputeByName; }
    public LocalDateTime getDisputeTime() { return disputeTime; }
    public void setDisputeTime(LocalDateTime disputeTime) { this.disputeTime = disputeTime; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public String getReviewByName() { return reviewByName; }
    public void setReviewByName(String reviewByName) { this.reviewByName = reviewByName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
