package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 异常扣罚规则（V14）：合同版本内按 异常类型 × 等级 配置。
 * grade 为空表示该异常类型通配所有等级（精确等级规则优先于通配）。
 * calcType：FIXED 定额扣罚 / PERCENT 按基础运费百分比（可设 cap_amount 封顶）。
 */
@TableName("t_cc_freight_penalty_rule")
public class FreightPenaltyRule extends CreateOnlyEntity {
    private Long contractId;
    private String exceptionType;
    private String exceptionTypeName;
    private String grade;
    private String calcType;
    private BigDecimal fixedAmount;
    private BigDecimal percent;
    private BigDecimal capAmount;
    private Boolean enabled;
    private String remark;

    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
    public String getExceptionTypeName() { return exceptionTypeName; }
    public void setExceptionTypeName(String exceptionTypeName) { this.exceptionTypeName = exceptionTypeName; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public String getCalcType() { return calcType; }
    public void setCalcType(String calcType) { this.calcType = calcType; }
    public BigDecimal getFixedAmount() { return fixedAmount; }
    public void setFixedAmount(BigDecimal fixedAmount) { this.fixedAmount = fixedAmount; }
    public BigDecimal getPercent() { return percent; }
    public void setPercent(BigDecimal percent) { this.percent = percent; }
    public BigDecimal getCapAmount() { return capAmount; }
    public void setCapAmount(BigDecimal capAmount) { this.capAmount = capAmount; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
