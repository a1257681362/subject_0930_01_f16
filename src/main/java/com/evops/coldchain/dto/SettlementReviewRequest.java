package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 争议项复核（财务）：included=true 扣罚维持计入；false 扣罚不计入并重算应付；复核意见必填 */
public class SettlementReviewRequest {
    @NotNull(message = "结算单ID不能为空")
    private Long settlementId;
    @NotNull(message = "争议费用项ID不能为空")
    private Long itemId;
    @NotNull(message = "复核结论（计入/不计入）不能为空")
    private Boolean included;
    @NotBlank(message = "复核意见不能为空")
    private String note;

    public Long getSettlementId() { return settlementId; }
    public void setSettlementId(Long settlementId) { this.settlementId = settlementId; }
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }
    public Boolean getIncluded() { return included; }
    public void setIncluded(Boolean included) { this.included = included; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
