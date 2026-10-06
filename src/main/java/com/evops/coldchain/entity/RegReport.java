package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 监管报表主表（V16）：一个“自然月 + 监管区域”一张报表。
 * 同一张报表可有多个只追加的版本（{@link RegReportVersion}）：
 * 定时按月生成 V1，重新计算只新增版本号，绝不覆盖历史版本/已下载文件。
 */
@TableName("t_cc_reg_report")
public class RegReport extends BaseEntity {
    private String reportNo;
    /** 统计自然月 yyyy-MM */
    private String reportMonth;
    /** 监管区域；空串表示全域汇总 */
    private String superviseRegion;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    /** 当前最新成功版本号；0 表示尚无成功版本（仅有失败尝试） */
    private Integer currentVersion;
    /** 最新一次生成状态：GENERATED / PENDING_SUPPLEMENT / FAILED */
    private String latestStatus;

    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }
    public String getReportMonth() { return reportMonth; }
    public void setReportMonth(String reportMonth) { this.reportMonth = reportMonth; }
    public String getSuperviseRegion() { return superviseRegion; }
    public void setSuperviseRegion(String superviseRegion) { this.superviseRegion = superviseRegion; }
    public LocalDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDateTime periodStart) { this.periodStart = periodStart; }
    public LocalDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDateTime periodEnd) { this.periodEnd = periodEnd; }
    public Integer getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(Integer currentVersion) { this.currentVersion = currentVersion; }
    public String getLatestStatus() { return latestStatus; }
    public void setLatestStatus(String latestStatus) { this.latestStatus = latestStatus; }
}
