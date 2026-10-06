package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 基础运费费率档（V14）：合同版本内按 箱型 × 里程区间[km_from, km_to)。
 * 费率档随合同版本只追加；km_to 为空表示该箱型最高开放档。
 */
@TableName("t_cc_freight_base_rate")
public class FreightBaseRate extends CreateOnlyEntity {
    private Long contractId;
    private String boxType;
    private String boxTypeName;
    private BigDecimal kmFrom;
    private BigDecimal kmTo;
    private BigDecimal pricePerKm;
    private BigDecimal minCharge;
    private Boolean enabled;

    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getBoxType() { return boxType; }
    public void setBoxType(String boxType) { this.boxType = boxType; }
    public String getBoxTypeName() { return boxTypeName; }
    public void setBoxTypeName(String boxTypeName) { this.boxTypeName = boxTypeName; }
    public BigDecimal getKmFrom() { return kmFrom; }
    public void setKmFrom(BigDecimal kmFrom) { this.kmFrom = kmFrom; }
    public BigDecimal getKmTo() { return kmTo; }
    public void setKmTo(BigDecimal kmTo) { this.kmTo = kmTo; }
    public BigDecimal getPricePerKm() { return pricePerKm; }
    public void setPricePerKm(BigDecimal pricePerKm) { this.pricePerKm = pricePerKm; }
    public BigDecimal getMinCharge() { return minCharge; }
    public void setMinCharge(BigDecimal minCharge) { this.minCharge = minCharge; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
