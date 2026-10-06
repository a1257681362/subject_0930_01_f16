package com.evops.coldchain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 旧数据回放请求：按目标合同版本（targetContractId）对历史已签收批次模拟计算，
 * 与基线版本（baselineContractId，空=目标版本发布前的生效版本）逐项对比。
 * batchIds 为空时回放全部已签收批次。
 * defaultBoxType/defaultMileageKm 为旧批次缺少锁价单/路线时的统一补录维度（仅回放计算用，不改批次）。
 */
public class FreightReplayRequest {
    private Long baselineContractId;
    private Long targetContractId;
    private List<Long> batchIds;
    private String defaultBoxType;
    private BigDecimal defaultMileageKm;

    public Long getBaselineContractId() { return baselineContractId; }
    public void setBaselineContractId(Long baselineContractId) { this.baselineContractId = baselineContractId; }
    public Long getTargetContractId() { return targetContractId; }
    public void setTargetContractId(Long targetContractId) { this.targetContractId = targetContractId; }
    public List<Long> getBatchIds() { return batchIds; }
    public void setBatchIds(List<Long> batchIds) { this.batchIds = batchIds; }
    public String getDefaultBoxType() { return defaultBoxType; }
    public void setDefaultBoxType(String defaultBoxType) { this.defaultBoxType = defaultBoxType; }
    public BigDecimal getDefaultMileageKm() { return defaultMileageKm; }
    public void setDefaultMileageKm(BigDecimal defaultMileageKm) { this.defaultMileageKm = defaultMileageKm; }
}
