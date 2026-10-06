package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 监管报表手动生成 / 重新计算请求（V16）。
 * 指定自然月（yyyy-MM）与监管区域（可空=全域汇总）。
 */
public class RegReportGenerateRequest {

    @NotBlank(message = "统计自然月不能为空（yyyy-MM）")
    @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "统计自然月格式必须为 yyyy-MM")
    private String reportMonth;

    /** 监管区域；空表示全域汇总 */
    private String superviseRegion;

    public String getReportMonth() { return reportMonth; }
    public void setReportMonth(String reportMonth) { this.reportMonth = reportMonth; }
    public String getSuperviseRegion() { return superviseRegion; }
    public void setSuperviseRegion(String superviseRegion) { this.superviseRegion = superviseRegion; }
}
