package com.evops.coldchain.dto;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

/**
 * 监管报表统计口径草稿（V16）：发布后只影响此后生成的报表，历史报表仍按各自绑定口径。
 * 各口径字段留空时由服务按内置基线补默认值（SHIP_TIME / TEMP_ALERT / 0 / REGISTER_TIME）。
 */
public class RegCaliberDraftRequest {
    @NotBlank(message = "口径版本名称不能为空")
    private String versionName;
    /** 发运量归属口径：SHIP_TIME 按发运时刻（默认） */
    private String shipmentBasis;
    /** 超温时长取数来源：TEMP_ALERT 温控预警连续超限时长（默认） */
    private String overtempSource;
    /** 异常关闭率关闭期限（天），0=不限制（默认）；不能为负 */
    @Min(value = 0, message = "关闭期限天数不能为负")
    private Integer closeWindowDays;
    /** 退货数量归属口径：REGISTER_TIME 按退货登记时间（默认） */
    private String returnBasis;
    /** 生成失败告警 webhook；空=仅站内错误链留痕 */
    private String notifyEndpoint;
    private String changeReason;

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }
    public String getShipmentBasis() { return shipmentBasis; }
    public void setShipmentBasis(String shipmentBasis) { this.shipmentBasis = shipmentBasis; }
    public String getOvertempSource() { return overtempSource; }
    public void setOvertempSource(String overtempSource) { this.overtempSource = overtempSource; }
    public Integer getCloseWindowDays() { return closeWindowDays; }
    public void setCloseWindowDays(Integer closeWindowDays) { this.closeWindowDays = closeWindowDays; }
    public String getReturnBasis() { return returnBasis; }
    public void setReturnBasis(String returnBasis) { this.returnBasis = returnBasis; }
    public String getNotifyEndpoint() { return notifyEndpoint; }
    public void setNotifyEndpoint(String notifyEndpoint) { this.notifyEndpoint = notifyEndpoint; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
}
