package com.evops.coldchain.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 评分规则草稿（V15）：四指标权重合计必须 100；发布后只影响此后开立的周期 */
public class ScoreRuleDraftRequest {
    @NotBlank(message = "版本名称不能为空")
    private String versionName;
    @NotNull(message = "准时率权重不能为空")
    @Min(value = 0, message = "权重不能为负")
    @Max(value = 100, message = "权重不能超过100")
    private Integer onTimeWeight;
    @NotNull(message = "温控合格率权重不能为空")
    @Min(value = 0, message = "权重不能为负")
    @Max(value = 100, message = "权重不能超过100")
    private Integer tempControlWeight;
    @NotNull(message = "签收完整度权重不能为空")
    @Min(value = 0, message = "权重不能为负")
    @Max(value = 100, message = "权重不能超过100")
    private Integer signCompleteWeight;
    @NotNull(message = "异常响应权重不能为空")
    @Min(value = 0, message = "权重不能为负")
    @Max(value = 100, message = "权重不能超过100")
    private Integer exceptionRespWeight;
    @NotNull(message = "申诉窗口天数不能为空")
    @Min(value = 1, message = "申诉窗口至少1天")
    private Integer appealWindowDays;
    @NotNull(message = "任务逾期天数不能为空")
    @Min(value = 1, message = "任务逾期天数至少1天")
    private Integer taskDueDays;
    /** 提醒 webhook；空=站内必达 */
    private String notifyEndpoint;
    private String changeReason;

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }
    public Integer getOnTimeWeight() { return onTimeWeight; }
    public void setOnTimeWeight(Integer onTimeWeight) { this.onTimeWeight = onTimeWeight; }
    public Integer getTempControlWeight() { return tempControlWeight; }
    public void setTempControlWeight(Integer tempControlWeight) { this.tempControlWeight = tempControlWeight; }
    public Integer getSignCompleteWeight() { return signCompleteWeight; }
    public void setSignCompleteWeight(Integer signCompleteWeight) { this.signCompleteWeight = signCompleteWeight; }
    public Integer getExceptionRespWeight() { return exceptionRespWeight; }
    public void setExceptionRespWeight(Integer exceptionRespWeight) { this.exceptionRespWeight = exceptionRespWeight; }
    public Integer getAppealWindowDays() { return appealWindowDays; }
    public void setAppealWindowDays(Integer appealWindowDays) { this.appealWindowDays = appealWindowDays; }
    public Integer getTaskDueDays() { return taskDueDays; }
    public void setTaskDueDays(Integer taskDueDays) { this.taskDueDays = taskDueDays; }
    public String getNotifyEndpoint() { return notifyEndpoint; }
    public void setNotifyEndpoint(String notifyEndpoint) { this.notifyEndpoint = notifyEndpoint; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
}
