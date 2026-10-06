package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 对费用项提出争议：争议项进入待复核（PENDING），结算单转 DISPUTED */
public class SettlementDisputeRequest {
    @NotNull(message = "结算单ID不能为空")
    private Long settlementId;
    @NotNull(message = "争议费用项ID不能为空")
    private Long itemId;
    @NotBlank(message = "争议原因不能为空")
    private String reason;

    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
