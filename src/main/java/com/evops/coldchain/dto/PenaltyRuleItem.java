package com.evops.coldchain.dto;

import java.math.BigDecimal;

/**
 * 合同版本内的一条异常扣罚规则：异常类型 × 等级（grade 空=通配）。
 * calcType=FIXED 时取 fixedAmount；calcType=PERCENT 时取 percent（0~100），
 * capAmount 可空表示不封顶。
 */
public class PenaltyRuleItem {
    private String exceptionType;
    private String grade;
    private String calcType;
    private BigDecimal fixedAmount;
    private BigDecimal percent;
    private BigDecimal capAmount;
    private String remark;

    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String exceptionType) { this.exceptionType = exceptionType; }
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
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
