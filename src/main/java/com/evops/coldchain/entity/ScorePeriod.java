package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 评分周期（V15）：开立时绑定当前生效规则版本并固化整版 JSON 快照；
 * 关闭（CLOSED）时对周期内有台账活动的承运商生成不可变评分快照，不可重开。
 */
@TableName("t_cc_score_period")
public class ScorePeriod extends BaseEntity {
    private String periodNo;
    private String periodName;
    private LocalDateTime periodStart;
    /** 归属判定 [periodStart, periodEnd) */
    private LocalDateTime periodEnd;
    /** OPEN / CLOSED */
    private String status;
    private Long ruleId;
    private Integer ruleVersionNo;
    /** 开立时整版规则 JSON 快照 */
    private String ruleSnapshot;
    private LocalDateTime closeTime;
    private Long closeBy;
    private String closeByName;
    private Integer snapshotCount;

    public String getPeriodNo() { return periodNo; }
    public void setPeriodNo(String periodNo) { this.periodNo = periodNo; }
    public String getPeriodName() { return periodName; }
    public void setPeriodName(String periodName) { this.periodName = periodName; }
    public LocalDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDateTime periodStart) { this.periodStart = periodStart; }
    public LocalDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDateTime periodEnd) { this.periodEnd = periodEnd; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleVersionNo() { return ruleVersionNo; }
    public void setRuleVersionNo(Integer ruleVersionNo) { this.ruleVersionNo = ruleVersionNo; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String ruleSnapshot) { this.ruleSnapshot = ruleSnapshot; }
    public LocalDateTime getCloseTime() { return closeTime; }
    public void setCloseTime(LocalDateTime closeTime) { this.closeTime = closeTime; }
    public Long getCloseBy() { return closeBy; }
    public void setCloseBy(Long closeBy) { this.closeBy = closeBy; }
    public String getCloseByName() { return closeByName; }
    public void setCloseByName(String closeByName) { this.closeByName = closeByName; }
    public Integer getSnapshotCount() { return snapshotCount; }
    public void setSnapshotCount(Integer snapshotCount) { this.snapshotCount = snapshotCount; }
}
