package com.evops.coldchain.dto;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/** 申诉复核（V15）：结论必须关联证据；复核同时产生改进任务；纠正值只落复核行不回写快照 */
public class ScoreAppealReviewRequest {
    /** UPHELD 申诉成立 / REJECTED 申诉驳回 */
    @NotBlank(message = "复核结论不能为空")
    private String conclusion;
    @NotBlank(message = "复核意见不能为空")
    private String reviewNote;
    @NotBlank(message = "复核结论必须关联证据")
    private String evidenceRefs;
    /** 复核纠正后的指标比率（可选；只落复核行） */
    private BigDecimal correctedRate;
    /** 复核纠正后的总分（可选；只落复核行） */
    private BigDecimal correctedTotalScore;
    /** 复核产生的改进任务（至少一条） */
    @NotNull(message = "复核必须产生改进任务")
    @Valid
    private List<ImproveTaskItem> tasks;

    /** 改进任务项：逾期天数缺省取规则快照 taskDueDays */
    public static class ImproveTaskItem {
        @NotBlank(message = "任务标题不能为空")
        private String title;
        @NotBlank(message = "任务内容不能为空")
        private String content;
        @NotBlank(message = "负责人不能为空")
        private String ownerName;
        private String ownerAccount;
        private Integer dueDays;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getOwnerName() { return ownerName; }
        public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
        public String getOwnerAccount() { return ownerAccount; }
        public void setOwnerAccount(String ownerAccount) { this.ownerAccount = ownerAccount; }
        public Integer getDueDays() { return dueDays; }
        public void setDueDays(Integer dueDays) { this.dueDays = dueDays; }
    }

    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public String getEvidenceRefs() { return evidenceRefs; }
    public void setEvidenceRefs(String evidenceRefs) { this.evidenceRefs = evidenceRefs; }
    public BigDecimal getCorrectedRate() { return correctedRate; }
    public void setCorrectedRate(BigDecimal correctedRate) { this.correctedRate = correctedRate; }
    public BigDecimal getCorrectedTotalScore() { return correctedTotalScore; }
    public void setCorrectedTotalScore(BigDecimal correctedTotalScore) { this.correctedTotalScore = correctedTotalScore; }
    public List<ImproveTaskItem> getTasks() { return tasks; }
    public void setTasks(List<ImproveTaskItem> tasks) { this.tasks = tasks; }
}
