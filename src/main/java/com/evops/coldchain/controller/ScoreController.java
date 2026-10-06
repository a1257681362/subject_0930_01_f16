package com.evops.coldchain.controller;

import com.evops.coldchain.dto.ImproveTaskDoneRequest;
import com.evops.coldchain.dto.RemindRetryRequest;
import com.evops.coldchain.dto.ScoreAppealReviewRequest;
import com.evops.coldchain.dto.ScoreAppealSubmitRequest;
import com.evops.coldchain.dto.ScorePeriodOpenRequest;
import com.evops.coldchain.dto.ScoreRuleDraftRequest;
import com.evops.coldchain.service.ImproveTaskService;
import com.evops.coldchain.service.ScoreAppealService;
import com.evops.coldchain.service.ScorePeriodService;
import com.evops.coldchain.service.ScoreRuleService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 承运商服务评分（V15）。
 *
 * 规则/周期：管理岗；快照台账：管理岗全量、承运商 /scores/my 只看自己；
 * 申诉：承运商（本人物件+窗口期）；复核：QA/管理员（非申诉人），产生改进任务；
 * 改进任务：完成确认只进不退，逾期自动提醒（失败不假装成功、恢复不重复）。
 */
@RestController
@RequestMapping("/api/cold-chain/scores")
public class ScoreController {

    private final ScoreRuleService ruleService;
    private final ScorePeriodService periodService;
    private final ScoreAppealService appealService;
    private final ImproveTaskService taskService;

    public ScoreController(ScoreRuleService ruleService, ScorePeriodService periodService,
                           ScoreAppealService appealService, ImproveTaskService taskService) {
        this.ruleService = ruleService;
        this.periodService = periodService;
        this.appealService = appealService;
        this.taskService = taskService;
    }

    // —— 评分规则（版本只追加；调整只影响下一周期）——

    @GetMapping("/rules")
    public ApiResponse<List<?>> rules() {
        return ApiResponse.ok(ruleService.list());
    }

    @PostMapping("/rules/draft")
    public ApiResponse<Object> draftRule(@Valid @RequestBody ScoreRuleDraftRequest req) {
        return ApiResponse.ok(ruleService.draft(req));
    }

    @PostMapping("/rules/{id}/publish")
    public ApiResponse<Object> publishRule(@PathVariable("id") Long id) {
        return ApiResponse.ok(ruleService.publish(id));
    }

    // —— 评分周期（开立绑规则；关闭生成不可变快照）——

    @GetMapping("/periods")
    public ApiResponse<List<?>> periods() {
        return ApiResponse.ok(periodService.list());
    }

    @PostMapping("/periods")
    public ApiResponse<Object> openPeriod(@Valid @RequestBody ScorePeriodOpenRequest req) {
        return ApiResponse.ok(periodService.open(req));
    }

    @PostMapping("/periods/{id}/close")
    public ApiResponse<Object> closePeriod(@PathVariable("id") Long id) {
        return ApiResponse.ok(periodService.close(id));
    }

    // —— 评分快照（管理岗全量台账）——

    @GetMapping("/snapshots")
    public ApiResponse<List<?>> snapshots() {
        return ApiResponse.ok(appealService.listSnapshots());
    }

    @GetMapping("/snapshots/{id}")
    public ApiResponse<Map<String, Object>> snapshotDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(appealService.detail(id));
    }

    // —— 承运商自己的指标与申诉入口 ——

    @GetMapping("/my")
    public ApiResponse<List<Map<String, Object>>> my() {
        return ApiResponse.ok(appealService.mySnapshots());
    }

    @GetMapping("/my/{snapshotId}")
    public ApiResponse<Map<String, Object>> myDetail(@PathVariable("snapshotId") Long snapshotId) {
        return ApiResponse.ok(appealService.myDetail(snapshotId));
    }

    // —— 申诉与复核（申诉期间原分数不回写；复核关联证据并产生改进任务）——

    /** 承运商提交申诉（独立子路径，与 GET 列表的管理岗权限区分开） */
    @PostMapping("/appeals/submit")
    public ApiResponse<Object> submitAppeal(@Valid @RequestBody ScoreAppealSubmitRequest req) {
        return ApiResponse.ok(appealService.submit(req));
    }

    @GetMapping("/appeals")
    public ApiResponse<List<?>> appeals() {
        return ApiResponse.ok(appealService.listAppeals());
    }

    @GetMapping("/appeals/{id}")
    public ApiResponse<Map<String, Object>> appealDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(appealService.appealDetail(id));
    }

    @PostMapping("/appeals/{id}/review")
    public ApiResponse<Object> reviewAppeal(@PathVariable("id") Long id,
                                            @Valid @RequestBody ScoreAppealReviewRequest req) {
        return ApiResponse.ok(appealService.review(id, req));
    }

    // —— 改进任务（逾期自动提醒负责人）——

    @GetMapping("/tasks")
    public ApiResponse<List<?>> tasks() {
        return ApiResponse.ok(taskService.listTasks());
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> taskDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(taskService.taskDetail(id));
    }

    @PostMapping("/tasks/{id}/done")
    public ApiResponse<Object> done(@PathVariable("id") Long id,
                                    @Valid @RequestBody ImproveTaskDoneRequest req) {
        return ApiResponse.ok(taskService.done(id, req.getDoneNote()));
    }

    @PostMapping("/tasks/{id}/remind-retry")
    public ApiResponse<Object> remindRetry(@PathVariable("id") Long id,
                                           @Valid @RequestBody RemindRetryRequest req) {
        return ApiResponse.ok(taskService.retry(id, req.getRetryReason()));
    }

    /** 手动触发一次逾期提醒扫描（与定时任务同一逻辑） */
    @PostMapping("/tasks/remind-scan")
    public ApiResponse<Map<String, Object>> remindScan() {
        return ApiResponse.ok(taskService.scanOverdue(LocalDateTime.now()));
    }
}
