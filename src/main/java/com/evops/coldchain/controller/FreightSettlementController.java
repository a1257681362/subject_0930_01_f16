package com.evops.coldchain.controller;

import com.evops.coldchain.dto.FreightReplayRequest;
import com.evops.coldchain.dto.SettlementAuditRequest;
import com.evops.coldchain.dto.SettlementDisputeRequest;
import com.evops.coldchain.dto.SettlementPreviewRequest;
import com.evops.coldchain.dto.SettlementReviewRequest;
import com.evops.coldchain.service.FreightReplayService;
import com.evops.coldchain.service.FreightSettlementService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 冷链运输费用预结算（V14）。
 *
 * 预览/生成：运营（generate 权限）；争议：运营（dispute）；
 * 争议复核/审核冻结：财务（review/audit）；旧数据回放：运营与财务（replay）。
 * 字段范围由 {@link FreightSettlementService#toView} 按财务/运营岗位收窄。
 */
@RestController
@RequestMapping("/api/cold-chain/freight")
public class FreightSettlementController {

    private final FreightSettlementService settlementService;
    private final FreightReplayService replayService;

    public FreightSettlementController(FreightSettlementService settlementService,
                                       FreightReplayService replayService) {
        this.settlementService = settlementService;
        this.replayService = replayService;
    }

    /** 预结算预览：逐项费用依据（不落库） */
    @PostMapping("/settlements/preview")
    public ApiResponse<Map<String, Object>> preview(@Valid @RequestBody SettlementPreviewRequest req) {
        return ApiResponse.ok(settlementService.preview(req));
    }

    /** 生成预结算单（规则+逐项快照） */
    @PostMapping("/settlements/generate")
    public ApiResponse<Map<String, Object>> generate(@Valid @RequestBody SettlementPreviewRequest req) {
        return ApiResponse.ok(settlementService.detail(settlementService.generate(req).getId()));
    }

    /** 结算单列表（字段按岗位收窄） */
    @GetMapping("/settlements")
    public ApiResponse<List<?>> settlements() {
        return ApiResponse.ok(settlementService.listSettlements());
    }

    /** 结算单详情（逐项费用依据；字段按岗位收窄） */
    @GetMapping("/settlements/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(settlementService.detail(id));
    }

    /** 对费用项提出争议（争议项进入待复核，结算单转 DISPUTED） */
    @PostMapping("/settlements/disputes")
    public ApiResponse<Map<String, Object>> dispute(@Valid @RequestBody SettlementDisputeRequest req) {
        settlementService.dispute(req);
        return ApiResponse.ok(settlementService.detail(req.getSettlementId()));
    }

    /** 财务复核争议项（计入/不计入，重算应付） */
    @PostMapping("/settlements/reviews")
    public ApiResponse<Map<String, Object>> review(@Valid @RequestBody SettlementReviewRequest req) {
        settlementService.review(req);
        return ApiResponse.ok(settlementService.detail(req.getSettlementId()));
    }

    /** 财务审核（审核后冻结，不被新规则重算） */
    @PostMapping("/settlements/audits")
    public ApiResponse<Map<String, Object>> audit(@Valid @RequestBody SettlementAuditRequest req) {
        settlementService.audit(req);
        return ApiResponse.ok(settlementService.detail(req.getSettlementId()));
    }

    /** 旧数据回放：基线版本 vs 新版本，逐项解释差异 */
    @PostMapping("/replays")
    public ApiResponse<Map<String, Object>> replay(@Valid @RequestBody FreightReplayRequest req) {
        return ApiResponse.ok(replayService.replay(req));
    }

    /** 回放报告列表 */
    @GetMapping("/replays")
    public ApiResponse<List<?>> replays() {
        return ApiResponse.ok(replayService.listReports());
    }

    /** 回放报告详情（逐批次差异解释） */
    @GetMapping("/replays/{id}")
    public ApiResponse<Map<String, Object>> replayDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(replayService.reportDetail(id));
    }
}
