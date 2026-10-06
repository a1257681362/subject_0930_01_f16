package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 改进任务（V15）：申诉复核产生；PENDING -> DONE 只进不退。
 * 逾期自动提醒负责人：remindStatus NONE -> SENT 仅在提醒真正送达后推进，
 * 外部依赖失败不置 SENT（不假装成功），恢复后恰好一条成功通知。
 */
@TableName("t_cc_improve_task")
public class ImproveTask extends BaseEntity {
    private String taskNo;
    /** 来源复核结论 */
    private Long reviewId;
    private Long appealId;
    private Long snapshotId;
    private Long carrierId;
    private String metricType;
    private String title;
    private String content;
    /** 负责人（提醒对象） */
    private String ownerName;
    /** 负责人账号（可选，便于站内定位） */
    private String ownerAccount;
    /** 逾期判定基准 */
    private LocalDateTime dueTime;
    /** PENDING / DONE */
    private String status;
    private LocalDateTime doneTime;
    private Long doneBy;
    private String doneByName;
    private String doneNote;
    /** NONE 未提醒 / SENT 已提醒（发送失败不置 SENT） */
    private String remindStatus;
    private LocalDateTime remindTime;

    public String getTaskNo() { return taskNo; }
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public Long getAppealId() { return appealId; }
    public void setAppealId(Long appealId) { this.appealId = appealId; }
    public Long getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Long snapshotId) { this.snapshotId = snapshotId; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getMetricType() { return metricType; }
    public void setMetricType(String metricType) { this.metricType = metricType; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getOwnerAccount() { return ownerAccount; }
    public void setOwnerAccount(String ownerAccount) { this.ownerAccount = ownerAccount; }
    public LocalDateTime getDueTime() { return dueTime; }
    public void setDueTime(LocalDateTime dueTime) { this.dueTime = dueTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getDoneTime() { return doneTime; }
    public void setDoneTime(LocalDateTime doneTime) { this.doneTime = doneTime; }
    public Long getDoneBy() { return doneBy; }
    public void setDoneBy(Long doneBy) { this.doneBy = doneBy; }
    public String getDoneByName() { return doneByName; }
    public void setDoneByName(String doneByName) { this.doneByName = doneByName; }
    public String getDoneNote() { return doneNote; }
    public void setDoneNote(String doneNote) { this.doneNote = doneNote; }
    public String getRemindStatus() { return remindStatus; }
    public void setRemindStatus(String remindStatus) { this.remindStatus = remindStatus; }
    public LocalDateTime getRemindTime() { return remindTime; }
    public void setRemindTime(LocalDateTime remindTime) { this.remindTime = remindTime; }
}
