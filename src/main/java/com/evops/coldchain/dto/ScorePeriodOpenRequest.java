package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 评分周期开立（V15）：开立时绑定当前生效规则版本，规则调整只影响下一周期 */
public class ScorePeriodOpenRequest {
    @NotBlank(message = "周期名称不能为空")
    private String periodName;
    @NotNull(message = "周期开始时间不能为空")
    private LocalDateTime periodStart;
    @NotNull(message = "周期结束时间不能为空")
    private LocalDateTime periodEnd;

    public String getPeriodName() { return periodName; }
    public void setPeriodName(String periodName) { this.periodName = periodName; }
    public LocalDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDateTime periodStart) { this.periodStart = periodStart; }
    public LocalDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDateTime periodEnd) { this.periodEnd = periodEnd; }
}
