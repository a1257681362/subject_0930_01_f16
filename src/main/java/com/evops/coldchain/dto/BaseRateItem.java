package com.evops.coldchain.dto;

import java.math.BigDecimal;

/** 合同版本内的一条基础运费费率档：箱型 × 里程区间[kmFrom, kmTo) 单价与最低收费 */
public class BaseRateItem {
    private String boxType;
    private BigDecimal kmFrom;
    /** 空=该箱型最高开放档 */
    private BigDecimal kmTo;
    private BigDecimal pricePerKm;
    private BigDecimal minCharge;

    public String getBoxType() { return boxType; }
    public void setBoxType(String boxType) { this.boxType = boxType; }
    public BigDecimal getKmFrom() { return kmFrom; }
    public void setKmFrom(BigDecimal kmFrom) { this.kmFrom = kmFrom; }
    public BigDecimal getKmTo() { return kmTo; }
    public void setKmTo(BigDecimal kmTo) { this.kmTo = kmTo; }
    public BigDecimal getPricePerKm() { return pricePerKm; }
    public void setPricePerKm(BigDecimal pricePerKm) { this.pricePerKm = pricePerKm; }
    public BigDecimal getMinCharge() { return minCharge; }
    public void setMinCharge(BigDecimal minCharge) { this.minCharge = minCharge; }
}
