package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/** 结算审核（财务）：审核通过即冻结，金额与规则快照此后不被任何新合同版本重算 */
public class SettlementAuditRequest {
    @NotNull(message = "结算单ID不能为空")
    private Long settlementId;
    private String auditRemark;

    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public String getAuditRemark() { return auditRemark; }
    public void setAuditRemark(String auditRemark) { this.auditRemark = auditRemark; }
}
