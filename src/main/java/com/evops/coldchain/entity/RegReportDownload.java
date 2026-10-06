package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 监管报表导出/下载留痕（V16，只追加）：每次 CSV 导出一行，记录导出行数与内容 SHA-256、
 * 含统计月+版本号+报表编号的文件名（绝不与旧版本同名）。
 * 列表/详情/导出/操作日志可据此交叉核对——同一不可变版本重复导出，内容摘要应一致。
 */
@TableName("t_cc_reg_report_download")
public class RegReportDownload extends CreateOnlyEntity {
    private Long reportId;
    private String reportNo;
    private String statMonth;
    private Integer versionNo;
    /** UI / API */
    private String channel;
    private Integer exportRows;
    private String contentSha256;
    private String fileName;
    private String operator;

    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }
    public String getStatMonth() { return statMonth; }
    public void setStatMonth(String statMonth) { this.statMonth = statMonth; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Integer getExportRows() { return exportRows; }
    public void setExportRows(Integer exportRows) { this.exportRows = exportRows; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
