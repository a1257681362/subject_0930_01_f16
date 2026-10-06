package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 预结算预览/生成请求：
 * - contractId 为空时按当前生效合同版本计算；
 * - boxType / mileageKm 为覆盖值：箱型默认取批次锁价单快照，里程默认取运输路线 total_km；
 *   无路线批次必须补录里程。
 */
public class SettlementPreviewRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    private Long contractId;
    private String boxType;
    private BigDecimal mileageKm;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getBoxType() { return boxType; }
    public void setBoxType(String boxType) { this.boxType = boxType; }
    public BigDecimal getMileageKm() { return mileageKm; }
    public void setMileageKm(BigDecimal mileageKm) { this.mileageKm = mileageKm; }
}
