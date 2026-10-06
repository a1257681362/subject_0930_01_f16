package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 监管报表统计口径版本（V16）：版本只追加（DRAFT/EFFECTIVE/SUPERSEDED）。
 * 生成报表时绑定当前生效口径并固化整版 JSON 快照——口径调整只影响此后生成的报表。
 */
@TableName("t_cc_reg_report_caliber")
public class RegReportCaliber extends BaseEntity {
    private Integer versionNo;
    private String versionName;
    /** DRAFT / EFFECTIVE / SUPERSEDED */
    private String status;
    /** 发运量口径 SHIP_BATCH / SHIP_QTY */
    private String shipQtyBasis;
    /** 超温时长口径 TEMP_ALERT / TEMP_LOG */
    private String overtempSource;
    /** 异常关闭率口径 FOUND_MONTH / RESOLVE_MONTH */
    private String exceptionCloseBasis;
    /** 退货数量口径 REGISTER_MONTH */
    private String returnBasis;
    /** 发运量是否包含已撤回批次 */
    private Boolean includeRevoked;
    /** 生成失败告警 webhook；空=站内必达 */
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
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public Long getPublishBy() { return publishBy; }
    public void setPublishBy(Long publishBy) { this.publishBy = publishBy; }
    public String getPublishByName() { return publishByName; }
    public void setPublishByName(String publishByName) { this.publishByName = publishByName; }
}
