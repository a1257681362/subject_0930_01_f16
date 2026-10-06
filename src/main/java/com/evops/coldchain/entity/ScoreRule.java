package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 评分规则版本（V15）：版本只追加（DRAFT/EFFECTIVE/SUPERSEDED）。
 * 周期开立时绑定当前生效版本并固化快照——规则调整只影响此后开立的周期。
 */
@TableName("t_cc_score_rule")
public class ScoreRule extends BaseEntity {
    private Integer versionNo;
    private String versionName;
    /** DRAFT / EFFECTIVE / SUPERSEDED */
    private String status;
    private Integer onTimeWeight;
    private Integer tempControlWeight;
    private Integer signCompleteWeight;
    private Integer exceptionRespWeight;
    /** 快照生成后申诉窗口（天） */
    private Integer appealWindowDays;
    /** 改进任务默认逾期天数 */
    private Integer taskDueDays;
    /** 提醒 webhook；空=站内必达 */
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
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public Long getPublishBy() { return publishBy; }
    public void setPublishBy(Long publishBy) { this.publishBy = publishBy; }
    public String getPublishByName() { return publishByName; }
    public void setPublishByName(String publishByName) { this.publishByName = publishByName; }
}
