package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 承运商周期评分快照（V15，不可变）：周期关闭时一次写入，
 * 应用层没有任何更新/删除路径；申诉复核结论、纠正值均不落本表。
 * 比率为 NULL 表示该指标无样本（不假装成功）；总分 NULL 表示四项全无样本。
 */
@TableName("t_cc_score_snapshot")
public class ScoreSnapshot extends CreateOnlyEntity {
    private Long periodId;
    private String periodNo;
    private Long carrierId;
    private String carrierNoSnap;
    private String carrierNameSnap;
    private Integer ruleVersionNo;
    /** 整版规则 JSON（与周期同源） */
    private String ruleSnapshot;

    private BigDecimal onTimeRate;
    private Integer onTimePass;
    private Integer onTimeFail;
    private Integer onTimeUnevaluable;

    private BigDecimal tempControlRate;
    private Integer tempControlPass;
    private Integer tempControlFail;
    private Integer tempControlUnevaluable;

    private BigDecimal signCompleteRate;
    private Integer signCompletePass;
    private Integer signCompleteFail;
    private Integer signCompleteUnevaluable;

    private BigDecimal exceptionRespRate;
    private Integer exceptionRespPass;
    private Integer exceptionRespFail;
    private Integer exceptionRespUnevaluable;

    /** 有样本参与总分的指标数 */
    private Integer scoredMetricCount;
    /** 加权总分；四项全无样本为 NULL */
    private BigDecimal totalScore;
    private LocalDateTime generateTime;

    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }
    public String getPeriodNo() { return periodNo; }
    public void setPeriodNo(String periodNo) { this.periodNo = periodNo; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getCarrierNoSnap() { return carrierNoSnap; }
    public void setCarrierNoSnap(String carrierNoSnap) { this.carrierNoSnap = carrierNoSnap; }
    public String getCarrierNameSnap() { return carrierNameSnap; }
    public void setCarrierNameSnap(String carrierNameSnap) { this.carrierNameSnap = carrierNameSnap; }
    public Integer getRuleVersionNo() { return ruleVersionNo; }
    public void setRuleVersionNo(Integer ruleVersionNo) { this.ruleVersionNo = ruleVersionNo; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
    public BigDecimal getOnTimeRate() { return onTimeRate; }
    public void setOnTimeRate(BigDecimal onTimeRate) { this.onTimeRate = onTimeRate; }
    public Integer getOnTimePass() { return onTimePass; }
    public void setOnTimePass(Integer onTimePass) { this.onTimePass = onTimePass; }
    public Integer getOnTimeFail() { return onTimeFail; }
    public void setOnTimeFail(Integer onTimeFail) { this.onTimeFail = onTimeFail; }
    public Integer getOnTimeUnevaluable() { return onTimeUnevaluable; }
    public void setOnTimeUnevaluable(Integer onTimeUnevaluable) { this.onTimeUnevaluable = onTimeUnevaluable; }
    public BigDecimal getTempControlRate() { return tempControlRate; }
    public void setTempControlRate(BigDecimal tempControlRate) { this.tempControlRate = tempControlRate; }
    public Integer getTempControlPass() { return tempControlPass; }
    public void setTempControlPass(Integer tempControlPass) { this.tempControlPass = tempControlPass; }
    public Integer getTempControlFail() { return tempControlFail; }
    public void setTempControlFail(Integer tempControlFail) { this.tempControlFail = tempControlFail; }
    public Integer getTempControlUnevaluable() { return tempControlUnevaluable; }
    public void setTempControlUnevaluable(Integer tempControlUnevaluable) { this.tempControlUnevaluable = tempControlUnevaluable; }
    public BigDecimal getSignCompleteRate() { return signCompleteRate; }
    public void setSignCompleteRate(BigDecimal signCompleteRate) { this.signCompleteRate = signCompleteRate; }
    public Integer getSignCompletePass() { return signCompletePass; }
    public void setSignCompletePass(Integer signCompletePass) { this.signCompletePass = signCompletePass; }
    public Integer getSignCompleteFail() { return signCompleteFail; }
    public void setSignCompleteFail(Integer signCompleteFail) { this.signCompleteFail = signCompleteFail; }
    public Integer getSignCompleteUnevaluable() { return signCompleteUnevaluable; }
    public void setSignCompleteUnevaluable(Integer signCompleteUnevaluable) { this.signCompleteUnevaluable = signCompleteUnevaluable; }
    public BigDecimal getExceptionRespRate() { return exceptionRespRate; }
    public void setExceptionRespRate(BigDecimal exceptionRespRate) { this.exceptionRespRate = exceptionRespRate; }
    public Integer getExceptionRespPass() { return exceptionRespPass; }
    public void setExceptionRespPass(Integer exceptionRespPass) { this.exceptionRespPass = exceptionRespPass; }
    public Integer getExceptionRespFail() { return exceptionRespFail; }
    public void setExceptionRespFail(Integer exceptionRespFail) { this.exceptionRespFail = exceptionRespFail; }
    public Integer getExceptionRespUnevaluable() { return exceptionRespUnevaluable; }
    public void setExceptionRespUnevaluable(Integer exceptionRespUnevaluable) { this.exceptionRespUnevaluable = exceptionRespUnevaluable; }
    public Integer getScoredMetricCount() { return scoredMetricCount; }
    public void setScoredMetricCount(Integer scoredMetricCount) { this.scoredMetricCount = scoredMetricCount; }
    public BigDecimal getTotalScore() { return totalScore; }
    public void setTotalScore(BigDecimal totalScore) { this.totalScore = totalScore; }
    public LocalDateTime getGenerateTime() { return generateTime; }
    public void setGenerateTime(LocalDateTime generateTime) { this.generateTime = generateTime; }
}
