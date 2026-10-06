package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.FreightReplayRequest;
import com.evops.coldchain.entity.FreightContract;
import com.evops.coldchain.entity.FreightReplay;
import com.evops.coldchain.entity.FreightReplayItem;
import com.evops.coldchain.entity.FreightSettlement;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.FreightReplayItemMapper;
import com.evops.coldchain.mapper.FreightReplayMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷链药品运输旧数据回放（V14）。
 *
 * 对历史已签收批次按基线合同版本与新版本各模拟计算一遍，逐项对齐基础运费与每条异常扣罚，
 * 输出可解释的差异报告（t_cc_freight_replay / *_item）。
 * 已财务审核（AUDITED）结算的批次按冻结单回放：基线/目标都取冻结金额，差异恒 0 并注明
 * “已审核冻结，新规则不重算”，单独计入 frozen_count。
 */
@Service
public class FreightReplayService {

    private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    @Resource
    private FreightReplayMapper replayMapper;
    @Resource
    private FreightReplayItemMapper replayItemMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private FreightContractService contractService;
    @Resource
    private FreightPricingEngine engine;
    @Resource
    private FreightSettlementService settlementService;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    @Transactional
    public Map<String, Object> replay(FreightReplayRequest req) {
        // 1) 解析目标版本与基线版本
        FreightContract target = req.getTargetContractId() == null
                ? contractService.effectiveContract()
                : contractService.requireContract(req.getTargetContractId());
        FreightContract baseline = req.getBaselineContractId() == null
                ? previousVersion(target) : contractService.requireContract(req.getBaselineContractId());
        if (baseline.getVersionNo().equals(target.getVersionNo())) {
            throw new BusinessException("回放基线版本与目标版本不能相同：V" + target.getVersionNo());
        }
        FreightContractService.ContractRules baseRules = contractService.rulesOf(baseline.getId());
        FreightContractService.ContractRules targetRules = contractService.rulesOf(target.getId());

        // 2) 选取历史已签收批次
        List<TransportBatch> batches;
        if (req.getBatchIds() != null && !req.getBatchIds().isEmpty()) {
            batches = batchMapper.selectBatchIds(req.getBatchIds());
        } else {
            batches = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                    .eq("status", CcConstants.BATCH_SIGNED).orderByAsc("id"));
        }

        FreightReplay report = new FreightReplay();
        report.setReplayNo("RP" + sequenceMapper.nextReplayNo());
        report.setBaselineContractId(baseline.getId());
        report.setBaselineVersionNo(baseline.getVersionNo());
        report.setTargetContractId(target.getId());
        report.setTargetVersionNo(target.getVersionNo());
        try {
            report.setScopeJson(M.writeValueAsString(new LinkedHashMap<String, Object>() {{
                put("batchIds", req.getBatchIds());
                put("defaultBoxType", req.getDefaultBoxType());
                put("defaultMileageKm", req.getDefaultMileageKm());
            }}));
        } catch (Exception ignored) {
            report.setScopeJson(null);
        }
        report.setBatchCount(batches.size());

        int changed = 0;
        int frozen = 0;
        List<String> skipped = new ArrayList<>();
        List<FreightReplayItem> rows = new ArrayList<>();
        for (TransportBatch batch : batches) {
            FreightReplayItem row = new FreightReplayItem();
            row.setBatchId(batch.getId());
            row.setBatchNo(batch.getBatchNo());

            FreightSettlement audited = settlementService.findByBatch(batch.getId());
            boolean isFrozen = audited != null && CcConstants.FST_AUDITED.equals(audited.getStatus());
            if (isFrozen) {
                // 已审核冻结：按冻结单回放，差异恒 0，新规则不重算
                frozen++;
                row.setAuditedSettlementId(audited.getId());
                row.setFrozen(true);
                row.setBaselineBaseAmount(audited.getBaseAmount());
                row.setBaselinePenaltyAmount(audited.getPenaltyAmount());
                row.setBaselinePayable(audited.getPayableAmount());
                row.setTargetBaseAmount(audited.getBaseAmount());
                row.setTargetPenaltyAmount(audited.getPenaltyAmount());
                row.setTargetPayable(audited.getPayableAmount());
                row.setDiffAmount(BigDecimal.ZERO.setScale(2));
                row.setDiffDetail(writeDetail(new ArrayList<String>() {{
                    add("批次结算单 " + audited.getSettlementNo() + " 已财务审核冻结（合同 V"
                            + audited.getContractVersionNo() + "），新版本 V" + target.getVersionNo()
                            + " 不得重算，基线/目标均按冻结金额，差异 0");
                }}));
                rows.add(row);
                continue;
            }

            FreightPricingEngine.CalcResult baseCalc;
            FreightPricingEngine.CalcResult targetCalc;
            try {
                baseCalc = engine.calculate(batch, baseRules, req.getDefaultBoxType(), req.getDefaultMileageKm());
                targetCalc = engine.calculate(batch, targetRules, req.getDefaultBoxType(), req.getDefaultMileageKm());
            } catch (BusinessException e) {
                skipped.add(batch.getBatchNo() + "：" + e.getMessage());
                continue;
            }

            row.setFrozen(false);
            row.setBaselineBaseAmount(baseCalc.baseAmount);
            row.setBaselinePenaltyAmount(baseCalc.penaltyAmount);
            row.setBaselinePayable(baseCalc.payableAmount);
            row.setTargetBaseAmount(targetCalc.baseAmount);
            row.setTargetPenaltyAmount(targetCalc.penaltyAmount);
            row.setTargetPayable(targetCalc.payableAmount);
            BigDecimal diff = PriceMath.money(targetCalc.payableAmount.subtract(baseCalc.payableAmount));
            row.setDiffAmount(diff);
            List<String> explanations = explain(baseCalc, targetCalc, baseline.getVersionNo(), target.getVersionNo());
            row.setDiffDetail(writeDetail(explanations));
            if (diff.signum() != 0) {
                changed++;
            }
            rows.add(row);
        }

        report.setChangedCount(changed);
        report.setFrozenCount(frozen);
        report.setResult(CcConstants.REPLAY_SUCCESS);
        StringBuilder detail = new StringBuilder();
        detail.append(String.format("回放 %d 批（基线 V%d -> 目标 V%d）：应付变化 %d 批，已审核冻结不重算 %d 批，实际模拟 %d 批",
                batches.size(), baseline.getVersionNo(), target.getVersionNo(), changed, frozen, rows.size() - frozen));
        if (!skipped.isEmpty()) {
            detail.append("；跳过 ").append(skipped.size()).append(" 批（缺少箱型/里程，可补录默认维度后重跑）");
        }
        report.setResultDetail(detail.toString());
        report.setReplayBy(UserContext.currentId());
        report.setReplayByName(UserContext.currentName());
        replayMapper.insert(report);
        for (FreightReplayItem row : rows) {
            row.setReplayId(report.getId());
            replayItemMapper.insert(row);
        }

        opLogService.log(CcConstants.ENT_FREIGHT_REPLAY, report.getId(), null,
                CcConstants.ACT_FREIGHT_REPLAY,
                String.format("旧数据回放 %s：基线合同 V%d -> 新版本 V%d「%s」，%s；操作者=%s",
                        report.getReplayNo(), baseline.getVersionNo(), target.getVersionNo(),
                        target.getVersionName(), detail, UserContext.currentName()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report", report);
        result.put("items", replayItemMapper.selectList(new QueryWrapper<FreightReplayItem>()
                .eq("replay_id", report.getId()).orderByAsc("id")));
        result.put("skipped", skipped);
        return result;
    }

    public List<FreightReplay> listReports() {
        return replayMapper.selectList(new QueryWrapper<FreightReplay>().orderByDesc("id"));
    }

    public Map<String, Object> reportDetail(Long replayId) {
        FreightReplay report = replayMapper.selectById(replayId);
        if (report == null) {
            throw new BusinessException("回放报告不存在: " + replayId);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("report", report);
        view.put("items", replayItemMapper.selectList(new QueryWrapper<FreightReplayItem>()
                .eq("replay_id", replayId).orderByAsc("id")));
        return view;
    }

    /** 目标版本之前最近的版本（基线） */
    private FreightContract previousVersion(FreightContract target) {
        List<FreightContract> older = contractService.listVersions().stream()
                .filter(c -> c.getVersionNo() < target.getVersionNo())
                .sorted((a, b) -> b.getVersionNo() - a.getVersionNo())
                .limit(1)
                .collect(java.util.stream.Collectors.toList());
        if (older.isEmpty()) {
            throw new BusinessException("合同 V" + target.getVersionNo() + " 之前没有可作为回放基线的版本");
        }
        return older.get(0);
    }

    /** 逐项对齐基础运费与每条异常扣罚，生成文字解释 */
    private List<String> explain(FreightPricingEngine.CalcResult base, FreightPricingEngine.CalcResult target,
                                 int baseVer, int targetVer) {
        List<String> lines = new ArrayList<>();
        FreightPricingEngine.CalcItem baseBase = base.baseItem();
        FreightPricingEngine.CalcItem targetBase = target.baseItem();
        if (baseBase != null && targetBase != null && baseBase.amount.compareTo(targetBase.amount) != 0) {
            lines.add(String.format("基础运费变化：V%d %s 元 -> V%d %s 元（%s %s 元）；V%d 口径：%s；V%d 口径：%s",
                    baseVer, baseBase.amount.toPlainString(),
                    targetVer, targetBase.amount.toPlainString(),
                    targetBase.amount.subtract(baseBase.amount).signum() >= 0 ? "增加" : "减少",
                    targetBase.amount.subtract(baseBase.amount).abs().toPlainString(),
                    baseVer, baseBase.basisPricing, targetVer, targetBase.basisPricing));
        }
        // 按异常单对齐扣罚
        Map<Long, FreightPricingEngine.CalcItem> basePen = penaltyMap(base);
        Map<Long, FreightPricingEngine.CalcItem> targetPen = penaltyMap(target);
        java.util.Set<Long> allIds = new java.util.TreeSet<>();
        allIds.addAll(basePen.keySet());
        allIds.addAll(targetPen.keySet());
        for (Long excId : allIds) {
            FreightPricingEngine.CalcItem b = basePen.get(excId);
            FreightPricingEngine.CalcItem t = targetPen.get(excId);
            if (b == null && t != null) {
                lines.add(String.format("异常单#%d（%s/%s）V%d 无扣罚约定 -> V%d 新增扣罚 %s 元（应付减少）；依据：%s",
                        excId, t.exceptionTypeName, t.gradeSnap, baseVer, targetVer,
                        t.amount.toPlainString(), t.basisPricing));
            } else if (b != null && t == null) {
                lines.add(String.format("异常单#%d（%s/%s）V%d 扣罚 %s 元 -> V%d 取消该扣罚（应付增加）",
                        excId, b.exceptionTypeName, b.gradeSnap, baseVer,
                        b.amount.toPlainString(), targetVer));
            } else if (b != null && b.amount.compareTo(t.amount) != 0) {
                lines.add(String.format("异常单#%d（%s/%s）扣罚变化：V%d %s 元 -> V%d %s 元（%s %s 元）；V%d：%s；V%d：%s",
                        excId, b.exceptionTypeName, b.gradeSnap,
                        baseVer, b.amount.toPlainString(), targetVer, t.amount.toPlainString(),
                        t.amount.subtract(b.amount).signum() >= 0 ? "多扣" : "少扣",
                        t.amount.subtract(b.amount).abs().toPlainString(),
                        baseVer, b.basisPricing, targetVer, t.basisPricing));
            }
        }
        if (lines.isEmpty()) {
            lines.add(String.format("两版计算结果一致：基础运费 %s 元、扣罚 %s 元、应付 %s 元，无差异",
                    base.baseAmount.toPlainString(), base.penaltyAmount.toPlainString(), base.payableAmount.toPlainString()));
        }
        lines.add(String.format("汇总：V%d 应付 %s 元（基础 %s - 扣罚 %s）-> V%d 应付 %s 元（基础 %s - 扣罚 %s），净差异 %s 元（正=新版本多付/少扣）",
                baseVer, base.payableAmount.toPlainString(), base.baseAmount.toPlainString(), base.penaltyAmount.toPlainString(),
                targetVer, target.payableAmount.toPlainString(), target.baseAmount.toPlainString(), target.penaltyAmount.toPlainString(),
                PriceMath.money(target.payableAmount.subtract(base.payableAmount)).toPlainString()));
        return lines;
    }

    private Map<Long, FreightPricingEngine.CalcItem> penaltyMap(FreightPricingEngine.CalcResult calc) {
        Map<Long, FreightPricingEngine.CalcItem> map = new LinkedHashMap<>();
        for (FreightPricingEngine.CalcItem item : calc.items) {
            if (CcConstants.FEE_PENALTY.equals(item.itemKind) && item.exceptionId != null) {
                map.put(item.exceptionId, item);
            }
        }
        return map;
    }

    private String writeDetail(List<String> lines) {
        try {
            return M.writeValueAsString(lines);
        } catch (Exception e) {
            return "[]";
        }
    }
}
