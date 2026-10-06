package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月度冷链运输监管报表（V16，不可变快照）：生成时一次写入，应用层没有任何更新/删除路径。
 *
 * 一月可有多版本（stat_month + version_no 唯一）：Quartz 定时生成首版，人工重算只追加下一版本，
 * 绝不覆盖旧版本，已下载文件（按版本号命名）随之永久可复核。
 *
 * 缺关键数据“标待补”而非静默填零：待补指标数值列记 NULL、键列入 {@link #pendingMetrics}、
 * 原因写入 {@link #supplementNote}，整体 status=PENDING_SUPPLEMENT；源记录为 0 行才记 0（真零）。
 * 生成失败不产生本表行（错误链路落 t_cc_reg_report_error 并告警）。
 */
@TableName("t_cc_reg_report")
public class RegReport extends CreateOnlyEntity {
    private String reportNo;
    /** 统计月 yyyy-MM */
    private String statMonth;
    /** 同统计月内版本号，从 1 递增；重算只增版本 */
    private Integer versionNo;
    /** SUCCESS / PENDING_SUPPLEMENT */
    private String status;
    private Long caliberId;
    private Integer caliberVersionNo;
    /** 生成时整版口径 JSON 快照 */
    private String caliberSnapshot;
    private LocalDateTime periodStart;
    /** 归属判定 [periodStart, periodEnd) */
    private LocalDateTime periodEnd;
    /** SCHEDULED / MANUAL */
    private String triggerSource;
    private LocalDateTime generateTime;
    private Long generatedBy;
    private String generatedByName;

    // —— 发运量 ——
    private Integer shippedBatchCount;
    private Integer shipQtyTotal;
    // —— 超温时长（分钟）——
    private Long overtempMinutes;
    // —— 异常关闭率 ——
    private Integer exceptionTotalCount;
    private Integer exceptionClosedCount;
    private BigDecimal exceptionCloseRate;
    // —— 退货数量 ——
    private Integer returnQtyTotal;

    /** 待补指标键逗号分隔，齐备为空串 */
    private String pendingMetrics;
    /** 各待补指标缺数原因 JSON（指标 -> 原因），齐备为空 */
    private String supplementNote;

    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }
    public String getStatMonth() { return statMonth; }
    public void setStatMonth(String statMonth) { this.statMonth = statMonth; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCaliberId() { return caliberId; }
    public void setCaliberId(Long caliberId) { this.caliberId = caliberId; }
    public Integer getCaliberVersionNo() { return caliberVersionNo; }
    public void setCaliberVersionNo(Integer caliberVersionNo) { this.caliberVersionNo = caliberVersionNo; }
    public String getCaliberSnapshot() { return caliberSnapshot; }
    public void setCaliberSnapshot(String caliberSnapshot) { this.caliberSnapshot = caliberSnapshot; }
    public LocalDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDateTime periodStart) { this.periodStart = periodStart; }
    public LocalDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDateTime periodEnd) { this.periodEnd = periodEnd; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public LocalDateTime getGenerateTime() { return generateTime; }
    public void setGenerateTime(LocalDateTime generateTime) { this.generateTime = generateTime; }
    public Long getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(Long generatedBy) { this.generatedBy = generatedBy; }
    public String getGeneratedByName() { return generatedByName; }
    public void setGeneratedByName(String generatedByName) { this.generatedByName = generatedByName; }
    public Integer getShippedBatchCount() { return shippedBatchCount; }
    public void setShippedBatchCount(Integer shippedBatchCount) { this.shippedBatchCount = shippedBatchCount; }
    public Integer getShipQtyTotal() { return shipQtyTotal; }
    public void setShipQtyTotal(Integer shipQtyTotal) { this.shipQtyTotal = shipQtyTotal; }
    public Long getOvertempMinutes() { return overtempMinutes; }
    public void setOvertempMinutes(Long overtempMinutes) { this.overtempMinutes = overtempMinutes; }
    public Integer getExceptionTotalCount() { return exceptionTotalCount; }
    public void setExceptionTotalCount(Integer exceptionTotalCount) { this.exceptionTotalCount = exceptionTotalCount; }
    public Integer getExceptionClosedCount() { return exceptionClosedCount; }
    public void setExceptionClosedCount(Integer exceptionClosedCount) { this.exceptionClosedCount = exceptionClosedCount; }
    public BigDecimal getExceptionCloseRate() { return exceptionCloseRate; }
    public void setExceptionCloseRate(BigDecimal exceptionCloseRate) { this.exceptionCloseRate = exceptionCloseRate; }
    public Integer getReturnQtyTotal() { return returnQtyTotal; }
    public void setReturnQtyTotal(Integer returnQtyTotal) { this.returnQtyTotal = returnQtyTotal; }
    public String getPendingMetrics() { return pendingMetrics; }
    public void setPendingMetrics(String pendingMetrics) { this.pendingMetrics = pendingMetrics; }
    public String getSupplementNote() { return supplementNote; }
    public void setSupplementNote(String supplementNote) { this.supplementNote = supplementNote; }
}
