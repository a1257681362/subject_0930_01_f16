package com.evops.coldchain.controller;

import com.evops.coldchain.dto.ContractDraftRequest;
import com.evops.coldchain.entity.FreightContract;
import com.evops.coldchain.service.FreightContractService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运输费用合同版本（V14）。
 * - 查看版本序列/版本规则：持结算查看权的财务与运营均可（运营用于核对）
 * - 起草/发布新版本：coldchain:freight:contract:manage（财务岗）
 */
@RestController
@RequestMapping("/api/cold-chain/freight/contracts")
public class FreightContractController {

    private final FreightContractService contractService;

    public FreightContractController(FreightContractService contractService) {
        this.contractService = contractService;
    }

    /** 合同版本序列 */
    @GetMapping
    public ApiResponse<List<FreightContract>> versions() {
        return ApiResponse.ok(contractService.listVersions());
    }

    /** 当前生效版本 */
    @GetMapping("/effective")
    public ApiResponse<FreightContract> effective() {
        return ApiResponse.ok(contractService.effectiveContract());
    }

    /** 版本完整规则（基础费率档 + 异常扣罚规则） */
    @GetMapping("/{id}/rules")
    public ApiResponse<Map<String, Object>> rules(@PathVariable("id") Long id) {
        FreightContractService.ContractRules rules = contractService.rulesOf(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("contract", rules.contract);
        view.put("baseRates", rules.baseRates);
        view.put("penaltyRules", rules.penaltyRules);
        return ApiResponse.ok(view);
    }

    /** 起草新版本（DRAFT，不影响任何已生成结算） */
    @PostMapping("/draft")
    public ApiResponse<FreightContract> draft(@Valid @RequestBody ContractDraftRequest req) {
        return ApiResponse.ok(contractService.draft(req));
    }

    /** 发布新版本（热替换当前生效版本；只影响此后生成的预结算/回放） */
    @PostMapping("/{id}/publish")
    public ApiResponse<FreightContract> publish(@PathVariable("id") Long id) {
        return ApiResponse.ok(contractService.publish(id));
    }
}
