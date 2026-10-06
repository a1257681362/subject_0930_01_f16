package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 逾期提醒手动重试（V15）：必须填写重试原因；已成功送达的拒绝重试 */
public class RemindRetryRequest {
    @NotBlank(message = "重试原因不能为空")
    private String retryReason;

    public String getRetryReason() { return retryReason; }
    public void setRetryReason(String retryReason) { this.retryReason = retryReason; }
}
