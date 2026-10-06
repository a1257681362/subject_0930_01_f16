package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 监管报表统计口径版本（V16）：版本只追加（DRAFT/EFFECTIVE/SUPERSEDED）。
 * 报表生成时绑定当前生效版本并固化整版 JSON 快照——口径调整只影响此后生成的报表，
 * 历史月报表仍按各自绑定的口径展示。
 */
@TableName("t_cc_reg_report_caliber")
public class RegReportCaliber extends BaseEntity {
    private Integer versionNo;
    private String versionName;
    /** DRAFT / EFFECTIVE / SUPERSEDED */
    private String status;
    /** 发运量归属口径：SHIP_TIME 按发运时刻 */
    private String shipmentBasis;
    /** 超温时长取数来源：TEMP_ALERT 按月度内触发的温控预警连续超限时长合计 */
    private String overtempSource;
    /** 异常关闭率关闭期限（天）；0=不限制，生成时点已关闭即计入分子 */
    private Integer closeWindowDays;
    /** 退货数量归属口径：REGISTER_TIME 按退货登记时间 */
    private String returnBasis;
    /** 生成失败告警 webhook；空=仅站内错误链留痕 */
    private String notifyEndpoint;
    private String changeReason;
    private LocalDateTime publishTime;
    private Long publishBy;
    private String publishByName;

    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
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
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public Long getPublishBy() { return publishBy; }
    public void setPublishBy(Long publishBy) { this.publishBy = publishBy; }
    public String getPublishByName() { return publishByName; }
    public void setPublishByName(String publishByName) { this.publishByName = publishByName; }
}
