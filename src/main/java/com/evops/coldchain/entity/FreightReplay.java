package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 旧数据回放报告（V14）：对历史已签收批次按基线合同版本与目标新版本各模拟一遍。
 * 批次级逐项差异在 {@link FreightReplayItem}。已审核结算的批次按冻结单回放
 * （差异恒 0，frozen_count 单独计数）。
 */
@TableName("t_cc_freight_replay")
public class FreightReplay extends CreateOnlyEntity {
    private String replayNo;
    private Long baselineContractId;
    private Integer baselineVersionNo;
    private Long targetContractId;
    private Integer targetVersionNo;
    private String scopeJson;
    private Integer batchCount;
    private Integer changedCount;
    private Integer frozenCount;
    /** SUCCESS / FAILED */
    private String result;
    private String resultDetail;
    private Long replayBy;
    private String replayByName;
    private LocalDateTime replayTime;

    public String getReplayNo() { return replayNo; }
    public void setReplayNo(String replayNo) { this.replayNo = replayNo; }
    public Long getBaselineContractId() { return baselineContractId; }
    public void setBaselineContractId(Long baselineContractId) { this.baselineContractId = baselineContractId; }
    public Integer getBaselineVersionNo() { return baselineVersionNo; }
    public void setBaselineVersionNo(Integer baselineVersionNo) { this.baselineVersionNo = baselineVersionNo; }
    public Long getTargetContractId() { return targetContractId; }
    public void setTargetContractId(Long targetContractId) { this.targetContractId = targetContractId; }
    public Integer getTargetVersionNo() { return targetVersionNo; }
    public void setTargetVersionNo(Integer targetVersionNo) { this.targetVersionNo = targetVersionNo; }
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String scopeJson) { this.scopeJson = scopeJson; }
    public Integer getBatchCount() { return batchCount; }
    public void setBatchCount(Integer batchCount) { this.batchCount = batchCount; }
    public Integer getChangedCount() { return changedCount; }
    public void setChangedCount(Integer changedCount) { this.changedCount = changedCount; }
    public Integer getFrozenCount() { return frozenCount; }
    public void setFrozenCount(Integer frozenCount) { this.frozenCount = frozenCount; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getResultDetail() { return resultDetail; }
    public void setResultDetail(String resultDetail) { this.resultDetail = resultDetail; }
    public Long getReplayBy() { return replayBy; }
    public void setReplayBy(Long replayBy) { this.replayBy = replayBy; }
    public String getReplayByName() { return replayByName; }
    public void setReplayByName(String replayByName) { this.replayByName = replayByName; }
    public LocalDateTime getReplayTime() { return replayTime; }
    public void setReplayTime(LocalDateTime replayTime) { this.replayTime = replayTime; }
}
