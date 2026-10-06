package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 监管报表版本快照（V16，不可变）：一次生成写入一行，应用层无更新/删除路径。
 * 重新计算只新增版本行（version_no 递增），绝不覆盖。
 * 指标值缺失/无样本为 NULL（绝不静默填 0）；缺关键数据时 status=PENDING_SUPPLEMENT。
 * 已下载（downloaded=TRUE）版本的文件不可覆盖，只能再生成新版本。
 */
@TableName("t_cc_reg_report_version")
public class RegReportVersion extends CreateOnlyEntity {
    private Long reportId;
    private String reportNo;
    private Integer versionNo;
    private Long caliberId;
    private Integer caliberVersionNo;
    /** 绑定口径的整版 JSON 快照 */
    private String caliberSnapshot;
    /** GENERATED / PENDING_SUPPLEMENT */
    private String status;

    private Long shipVolume;
    private Long overtempMinutes;
    private Integer exceptionTotal;
    private Integer exceptionClosed;
    /** 异常关闭率 %；分母为 0 记 NULL */
    private BigDecimal exceptionCloseRate;
    private Long returnQty;
    private Integer returnCount;

    /** 缺失指标代码，逗号分隔；空串=齐全 */
    private String missingMetrics;
    private String pendingNote;

    /** 版本化下载文件名（生成即固化） */
    private String fileName;
    private Boolean fileGenerated;
    /** 是否被下载过（只进不退）；已下载版本不可覆盖 */
    private Boolean downloaded;
    private LocalDateTime downloadTime;
    private String downloadByName;

    /** SCHEDULED / MANUAL */
    private String triggerSource;
    private LocalDateTime generateTime;
    private String generateByName;

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public Long getCaliberId() { return caliberId; }
    public void setCaliberId(Long caliberId) { this.caliberId = caliberId; }
    public Integer getCaliberVersionNo() { return caliberVersionNo; }
    public void setCaliberVersionNo(Integer caliberVersionNo) { this.caliberVersionNo = caliberVersionNo; }
    public String getCaliberSnapshot() { return caliberSnapshot; }
    public void setCaliberSnapshot(String caliberSnapshot) { this.caliberSnapshot = caliberSnapshot; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getShipVolume() { return shipVolume; }
    public void setShipVolume(Long shipVolume) { this.shipVolume = shipVolume; }
    public Long getOvertempMinutes() { return overtempMinutes; }
    public void setOvertempMinutes(Long overtempMinutes) { this.overtempMinutes = overtempMinutes; }
    public Integer getExceptionTotal() { return exceptionTotal; }
    public void setExceptionTotal(Integer exceptionTotal) { this.exceptionTotal = exceptionTotal; }
    public Integer getExceptionClosed() { return exceptionClosed; }
    public void setExceptionClosed(Integer exceptionClosed) { this.exceptionClosed = exceptionClosed; }
    public BigDecimal getExceptionCloseRate() { return exceptionCloseRate; }
    public void setExceptionCloseRate(BigDecimal exceptionCloseRate) { this.exceptionCloseRate = exceptionCloseRate; }
    public Long getReturnQty() { return returnQty; }
    public void setReturnQty(Long returnQty) { this.returnQty = returnQty; }
    public Integer getReturnCount() { return returnCount; }
    public void setReturnCount(Integer returnCount) { this.returnCount = returnCount; }
    public String getMissingMetrics() { return missingMetrics; }
    public void setMissingMetrics(String missingMetrics) { this.missingMetrics = missingMetrics; }
    public String getPendingNote() { return pendingNote; }
    public void setPendingNote(String pendingNote) { this.pendingNote = pendingNote; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Boolean getFileGenerated() { return fileGenerated; }
    public void setFileGenerated(Boolean fileGenerated) { this.fileGenerated = fileGenerated; }
    public Boolean getDownloaded() { return downloaded; }
    public void setDownloaded(Boolean downloaded) { this.downloaded = downloaded; }
    public LocalDateTime getDownloadTime() { return downloadTime; }
    public void setDownloadTime(LocalDateTime downloadTime) { this.downloadTime = downloadTime; }
    public String getDownloadByName() { return downloadByName; }
    public void setDownloadByName(String downloadByName) { this.downloadByName = downloadByName; }
    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }
    public LocalDateTime getGenerateTime() { return generateTime; }
    public void setGenerateTime(LocalDateTime generateTime) { this.generateTime = generateTime; }
    public String getGenerateByName() { return generateByName; }
    public void setGenerateByName(String generateByName) { this.generateByName = generateByName; }
}
