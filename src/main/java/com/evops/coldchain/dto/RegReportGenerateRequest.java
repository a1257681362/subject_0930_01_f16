package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 手动生成/重算指定统计月的监管报表（V16）。
 * 该月无版本则生成 V1；已有版本则只追加下一版本（重算绝不覆盖旧版本/已下载文件）。
 */
public class RegReportGenerateRequest {
    /** 统计月 yyyy-MM */
    @NotBlank(message = "统计月不能为空")
    @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "统计月格式必须为 yyyy-MM")
    private String statMonth;

    public String getStatMonth() { return statMonth; }
    public void setStatMonth(String statMonth) { this.statMonth = statMonth; }
}
