package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 监管报表统计口径新版本起草请求（V16）。
 */
public class RegCaliberDraftRequest {

    @NotBlank(message = "口径版本名称不能为空")
    @Size(max = 128, message = "口径版本名称最长 128 字")
    private String versionName;

    /** 发运量口径 SHIP_BATCH / SHIP_QTY */
    @NotBlank(message = "发运量口径不能为空")
    private String shipQtyBasis;
    /** 超温时长口径 TEMP_ALERT / TEMP_LOG */
    @NotBlank(message = "超温时长口径不能为空")
    private String overtempSource;
    /** 异常关闭率口径 FOUND_MONTH / RESOLVE_MONTH */
    @NotBlank(message = "异常关闭率口径不能为空")
    private String exceptionCloseBasis;
    /** 退货数量口径 REGISTER_MONTH */
    @NotBlank(message = "退货数量口径不能为空")
    private String returnBasis;

    /** 发运量是否包含已撤回批次（默认 false） */
    @NotNull(message = "是否包含已撤回批次必须明确")
    private Boolean includeRevoked;

    /** 生成失败告警 webhook；空=站内必达 */
    private String notifyEndpoint;

    @NotBlank(message = "变更原因不能为空")
    @Size(max = 500, message = "变更原因最长 500 字")
    private String changeReason;

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }
    public String getShipQtyBasis() { return shipQtyBasis; }
    public void setShipQtyBasis(String shipQtyBasis) { this.shipQtyBasis = shipQtyBasis; }
    public String getOvertempSource() { return overtempSource; }
    public void setOvertempSource(String overtempSource) { this.overtempSource = overtempSource; }
    public String getExceptionCloseBasis() { return exceptionCloseBasis; }
    public void setExceptionCloseBasis(String exceptionCloseBasis) { this.exceptionCloseBasis = exceptionCloseBasis; }
    public String getReturnBasis() { return returnBasis; }
    public void setReturnBasis(String returnBasis) { this.returnBasis = returnBasis; }
    public Boolean getIncludeRevoked() { return includeRevoked; }
    public void setIncludeRevoked(Boolean includeRevoked) { this.includeRevoked = includeRevoked; }
    public String getNotifyEndpoint() { return notifyEndpoint; }
    public void setNotifyEndpoint(String notifyEndpoint) { this.notifyEndpoint = notifyEndpoint; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
}
