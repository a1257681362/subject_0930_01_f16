package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 申诉复核结论（V15，只追加）：必须关联证据（evidenceRefs 非空）；
 * 纠正值只落本表，原评分快照永不回写；视图按“原值+最新复核结论”并列。
 */
@TableName("t_cc_score_review")
public class ScoreReview extends CreateOnlyEntity {
    private Long appealId;
    private Long snapshotId;
    private Long carrierId;
    private String metricType;
    /** UPHELD 申诉成立 / REJECTED 申诉驳回 */
    private String conclusion;
    private String reviewNote;
    /** 复核结论关联证据（必填） */
    private String evidenceRefs;
    /** 复核纠正后的指标比率（可选；只落本行） */
    private BigDecimal correctedRate;
    /** 复核纠正后的总分（可选；只落本行） */
    private BigDecimal correctedTotalScore;
    private Long reviewBy;
    private String reviewByName;
    private LocalDateTime reviewTime;

    public Long getAppealId() { return appealId; }
    public void setAppealId(Long appealId) { this.appealId = appealId; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public String getEvidenceRefs() { return evidenceRefs; }
    public void setEvidenceRefs(String evidenceRefs) { this.evidenceRefs = evidenceRefs; }
    public BigDecimal getCorrectedRate() { return correctedRate; }
    public void setCorrectedRate(BigDecimal correctedRate) { this.correctedRate = correctedRate; }
    public BigDecimal getCorrectedTotalScore() { return correctedTotalScore; }
    public void setCorrectedTotalScore(BigDecimal correctedTotalScore) { this.correctedTotalScore = correctedTotalScore; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public String getReviewByName() { return reviewByName; }
    public void setReviewByName(String reviewByName) { this.reviewByName = reviewByName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
}
