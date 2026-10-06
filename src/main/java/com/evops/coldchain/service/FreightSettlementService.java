package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.SettlementAuditRequest;
import com.evops.coldchain.dto.SettlementDisputeRequest;
import com.evops.coldchain.dto.SettlementPreviewRequest;
import com.evops.coldchain.dto.SettlementReviewRequest;
import com.evops.coldchain.entity.FreightContract;
import com.evops.coldchain.entity.FreightSettleItem;
import com.evops.coldchain.entity.FreightSettlement;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.FreightSettleItemMapper;
import com.evops.coldchain.mapper.FreightSettlementMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运输费用预结算（V14）。
 *
 * 生命周期：预览（不落库）-> 生成（整版规则快照 + 逐项费用快照，PENDING）
 * -> 争议（争议项 PENDING，结算单 DISPUTED）-> 财务复核（扣罚计入/不计入，重算应付）
 * -> 财务审核（AUDITED，金额与快照永久冻结，任何新合同版本/回放不得重算）。
 *
 * 视图按岗位收窄：财务可见费率/单价/计价依据/规则快照/审核信息；
 * 运营只见业务依据、各项金额与争议状态。
 */
@Service
public class FreightSettlementService {

    private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    @Resource
    private FreightSettlementMapper settlementMapper;
    @Resource
    private FreightSettleItemMapper itemMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private FreightContractService contractService;
    @Resource
    private FreightPricingEngine engine;
    @Resource
    private ExceptionLifecycleService exceptionLifecycleService;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private com.evops.coldchain.mapper.LockedPriceMapper lockedPriceMapper;
    @Resource
    private OpLogService opLogService;

    /** 预览：按指定/当前生效合同版本模拟计算，不落库、不产生快照 */
    public Map<String, Object> preview(SettlementPreviewRequest req) {
        TransportBatch batch = requireSignedBatch(req.getBatchId());
        FreightContract contract = req.getContractId() == null
                ? contractService.effectiveContract() : requireEffectiveContract(req.getContractId());
        FreightContractService.ContractRules rules = contractService.rulesOf(contract.getId());
        FreightPricingEngine.CalcResult calc = engine.calculate(
                batch, rules, req.getBoxType(), req.getMileageKm());

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batchId", batch.getId());
        view.put("batchNo", batch.getBatchNo());
        view.put("contractId", contract.getId());
        view.put("contractVersionNo", contract.getVersionNo());
        view.put("contractVersionName", contract.getVersionName());
        view.put("boxType", calc.boxType);
        view.put("boxTypeName", calc.boxTypeName);
        view.put("mileageKm", calc.mileage);
        view.put("mileageSource", calc.mileageSource);
        view.put("baseAmount", calc.baseAmount);
        view.put("penaltyAmount", calc.penaltyAmount);
        view.put("payableAmount", calc.payableAmount);
        view.put("currency", "CNY");
        view.put("items", calc.items);
        view.put("unclosedExceptionCount", calc.unclosedCount);
        view.put("persisted", false);
        return view;
    }

    /** 生成预结算单：固化规则快照与逐项费用快照 */
    @Transactional
    public FreightSettlement generate(SettlementPreviewRequest req) {
        TransportBatch batch = requireSignedBatch(req.getBatchId());
        // 与 V6 结算同卡口：存在未关闭异常（待确认/处置中/待复核）不得进入费用结算
        exceptionLifecycleService.requireAllClosed(batch.getId(), "生成费用预结算");
        // 已审核冻结的批次不得重新生成；存在未审核结算单时也不重复生成（先处理争议/审核）
        FreightSettlement existing = findByBatch(batch.getId());
        if (existing != null) {
            if (CcConstants.FST_AUDITED.equals(existing.getStatus())) {
                throw new BusinessException(String.format(
                        "批次 %s 的结算单 %s 已财务审核冻结，不能按新规则重新生成",
                        batch.getBatchNo(), existing.getSettlementNo()));
            }
            throw new BusinessException(String.format(
                    "批次 %s 已存在预结算单 %s（状态=%s），不能重复生成；请处理争议或审核后再操作",
                    batch.getBatchNo(), existing.getSettlementNo(), existing.getStatus()));
        }

        FreightContract contract = req.getContractId() == null
                ? contractService.effectiveContract() : requireEffectiveContract(req.getContractId());
        FreightContractService.ContractRules rules = contractService.rulesOf(contract.getId());
        FreightPricingEngine.CalcResult calc = engine.calculate(
                batch, rules, req.getBoxType(), req.getMileageKm());
        if (calc.unclosedCount > 0) {
            throw new BusinessException("批次存在 " + calc.unclosedCount + " 条未关闭的扣罚类异常，不能生成预结算");
        }

        FreightSettlement settlement = new FreightSettlement();
        settlement.setSettlementNo("FS" + sequenceMapper.nextSettlementNo());
        settlement.setBatchId(batch.getId());
        settlement.setBatchNo(batch.getBatchNo());
        settlement.setCarrierNameSnap(carrierName(batch));
        settlement.setBoxTypeSnap(calc.boxType);
        settlement.setBoxTypeNameSnap(calc.boxTypeName);
        settlement.setMileageKmSnap(calc.mileage);
        settlement.setMileageSource(calc.mileageSource);
        settlement.setContractId(contract.getId());
        settlement.setContractVersionNo(contract.getVersionNo());
        settlement.setRuleSnapshot(buildRuleSnapshot(rules, calc));
        settlement.setBaseAmount(calc.baseAmount);
        settlement.setPenaltyAmount(calc.penaltyAmount);
        settlement.setPayableAmount(calc.payableAmount);
        settlement.setCurrency("CNY");
        settlement.setStatus(CcConstants.FST_PENDING);
        settlement.setGenerateTime(LocalDateTime.now());
        settlement.setGenerateBy(UserContext.currentId());
        settlement.setGenerateByName(UserContext.currentName());
        settlement.setDisputePendingCount(0);
        settlementMapper.insert(settlement);

        int seq = 1;
        for (FreightPricingEngine.CalcItem ci : calc.items) {
            FreightSettleItem item = new FreightSettleItem();
            item.setSettlementId(settlement.getId());
            item.setItemSeq(seq++);
            item.setItemKind(ci.itemKind);
            item.setTitle(ci.title);
            item.setExceptionId(ci.exceptionId);
            item.setExceptionType(ci.exceptionType);
            item.setExceptionTypeName(ci.exceptionTypeName);
            item.setGradeSnap(ci.gradeSnap);
            item.setCalcType(ci.calcType);
            item.setUnitPrice(ci.unitPrice);
            item.setQuantity(ci.quantity);
            item.setAmount(ci.amount);
            item.setIncluded(true);
            item.setBasisBusiness(ci.basisBusiness);
            item.setBasisPricing(ci.basisPricing);
            item.setRuleSnapshot(ci.ruleSnapshot);
            item.setDisputeStatus(CcConstants.DISPUTE_NONE);
            itemMapper.insert(item);
        }

        opLogService.log(CcConstants.ENT_FREIGHT_SETTLEMENT, settlement.getId(), batch.getId(),
                CcConstants.ACT_FREIGHT_SETTLE_GENERATE,
                String.format("批次 %s 生成预结算单 %s：合同 V%d「%s」，箱型=%s，里程=%skm，基础运费=%s，扣罚=%s，应付=%s；操作者=%s（规则已逐版本+逐项快照）",
                        batch.getBatchNo(), settlement.getSettlementNo(), contract.getVersionNo(),
                        contract.getVersionName(), calc.boxTypeName, calc.mileage,
                        calc.baseAmount, calc.penaltyAmount, calc.payableAmount, UserContext.currentName()));
        return settlement;
    }

    /** 提出争议：争议项进入待复核，结算单转 DISPUTED（已审核冻结单拒绝） */
    @Transactional
    public FreightSettleItem dispute(SettlementDisputeRequest req) {
        FreightSettlement settlement = requireSettlement(req.getSettlementId());
        requireNotAudited(settlement, "提出争议");
        FreightSettleItem item = requireItem(settlement.getId(), req.getItemId());
        if (CcConstants.FEE_BASE.equals(item.getItemKind())) {
            throw new BusinessException("基础运费争议应通过合同费率档核对处理，争议流程仅针对异常扣罚项");
        }
        if (CcConstants.DISPUTE_PENDING.equals(item.getDisputeStatus())) {
            throw new BusinessException("费用项已在待复核中，不能重复提出争议");
        }
        item.setDisputeStatus(CcConstants.DISPUTE_PENDING);
        item.setDisputeReason(req.getReason().trim());
        item.setDisputeBy(UserContext.currentId());
        item.setDisputeByName(UserContext.currentName());
        item.setDisputeTime(LocalDateTime.now());
        itemMapper.updateById(item);

        settlement.setDisputePendingCount(settlement.getDisputePendingCount() + 1);
        settlement.setStatus(CcConstants.FST_DISPUTED);
        settlementMapper.updateById(settlement);

        opLogService.log(CcConstants.ENT_FREIGHT_SETTLEMENT, settlement.getId(), settlement.getBatchId(),
                CcConstants.ACT_FREIGHT_SETTLE_DISPUTE,
                String.format("结算单 %s 费用项#%d「%s」提出争议（金额=%s），进入待复核；原因=%s；操作者=%s",
                        settlement.getSettlementNo(), item.getId(), item.getTitle(), item.getAmount(),
                        req.getReason(), UserContext.currentName()));
        return item;
    }

    /** 财务复核争议项：决定扣罚计入/不计入并重算应付；全部争议处理完回 PENDING */
    @Transactional
    public FreightSettleItem review(SettlementReviewRequest req) {
        FreightSettlement settlement = requireSettlement(req.getSettlementId());
        requireNotAudited(settlement, "争议复核");
        FreightSettleItem item = requireItem(settlement.getId(), req.getItemId());
        if (!CcConstants.DISPUTE_PENDING.equals(item.getDisputeStatus())) {
            throw new BusinessException("仅待复核（PENDING）费用项可以复核，当前争议状态: " + item.getDisputeStatus());
        }
        item.setDisputeStatus(Boolean.TRUE.equals(req.getIncluded())
                ? CcConstants.DISPUTE_INCLUDED : CcConstants.DISPUTE_EXCLUDED);
        item.setIncluded(Boolean.TRUE.equals(req.getIncluded()));
        item.setReviewBy(UserContext.currentId());
        item.setReviewByName(UserContext.currentName());
        item.setReviewTime(LocalDateTime.now());
        item.setReviewNote(req.getNote().trim());
        itemMapper.updateById(item);

        recalculate(settlement);
        opLogService.log(CcConstants.ENT_FREIGHT_SETTLEMENT, settlement.getId(), settlement.getBatchId(),
                CcConstants.ACT_FREIGHT_SETTLE_REVIEW,
                String.format("结算单 %s 费用项#%d「%s」财务复核：%s（金额=%s）；复核意见=%s；操作者=%s；重算后基础=%s 扣罚=%s 应付=%s",
                        settlement.getSettlementNo(), item.getId(), item.getTitle(),
                        Boolean.TRUE.equals(req.getIncluded()) ? "计入" : "不计入",
                        item.getAmount(), req.getNote(), UserContext.currentName(),
                        settlement.getBaseAmount(), settlement.getPenaltyAmount(), settlement.getPayableAmount()));
        return item;
    }

    /** 财务审核：存在待复核争议项时拦截；审核后金额与规则快照永久冻结 */
    @Transactional
    public FreightSettlement audit(SettlementAuditRequest req) {
        FreightSettlement settlement = requireSettlement(req.getSettlementId());
        if (CcConstants.FST_AUDITED.equals(settlement.getStatus())) {
            throw new BusinessException("结算单 " + settlement.getSettlementNo() + " 已审核冻结，不能重复审核");
        }
        if (settlement.getDisputePendingCount() > 0) {
            throw new BusinessException("结算单还有 " + settlement.getDisputePendingCount()
                    + " 个待复核争议项，全部复核完成后才能审核");
        }
        settlement.setStatus(CcConstants.FST_AUDITED);
        settlement.setAuditTime(LocalDateTime.now());
        settlement.setAuditBy(UserContext.currentId());
        settlement.setAuditByName(UserContext.currentName());
        settlement.setAuditRemark(req.getAuditRemark());
        settlementMapper.updateById(settlement);

        opLogService.log(CcConstants.ENT_FREIGHT_SETTLEMENT, settlement.getId(), settlement.getBatchId(),
                CcConstants.ACT_FREIGHT_SETTLE_AUDIT,
                String.format("结算单 %s 财务审核通过并冻结：应付=%s；备注=%s；操作者=%s。此后合同新版本/回放均不得重算本单",
                        settlement.getSettlementNo(), settlement.getPayableAmount(),
                        req.getAuditRemark() == null ? "" : req.getAuditRemark(), UserContext.currentName()));
        return settlement;
    }

    public FreightSettlement requireSettlement(Long id) {
        FreightSettlement settlement = id == null ? null : settlementMapper.selectById(id);
        if (settlement == null) {
            throw new BusinessException("费用结算单不存在: " + id);
        }
        return settlement;
    }

    public FreightSettlement findByBatch(Long batchId) {
        return settlementMapper.selectOne(new QueryWrapper<FreightSettlement>()
                .eq("batch_id", batchId).orderByDesc("id").last("LIMIT 1"));
    }

    public List<FreightSettlement> listSettlements() {
        return settlementMapper.selectList(new QueryWrapper<FreightSettlement>().orderByDesc("id"));
    }

    public List<FreightSettleItem> listItems(Long settlementId) {
        return itemMapper.selectList(new QueryWrapper<FreightSettleItem>()
                .eq("settlement_id", settlementId).orderByAsc("item_seq"));
    }

    /** 结算单详情（金额按当前 included 标志汇总；已审核单与落库冻结值一致） */
    public Map<String, Object> detail(Long settlementId) {
        FreightSettlement settlement = requireSettlement(settlementId);
        return toView(settlement, listItems(settlementId));
    }

    /** 组装视图并按当前岗位收窄字段范围（财务/运营） */
    public Map<String, Object> toView(FreightSettlement settlement, List<FreightSettleItem> items) {
        boolean finance = isFinanceViewer();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("settlement", finance ? settlementEntityToMap(settlement) : maskSettlement(settlement));
        List<Map<String, Object>> itemViews = new ArrayList<>();
        for (FreightSettleItem item : items) {
            itemViews.add(finance ? itemEntityToMap(item) : maskItem(item));
        }
        view.put("items", itemViews);
        view.put("viewerScope", finance ? "FINANCE" : "OPERATIONS");
        return view;
    }

    /** 当前查看人是否财务口径（FINANCE 角色或持有审核/复核权限点） */
    public boolean isFinanceViewer() {
        try {
            org.apache.shiro.subject.Subject subject = SecurityUtils.getSubject();
            return subject.hasRole(CcConstants.ROLE_FINANCE)
                    || subject.isPermitted(CcConstants.PERM_FREIGHT_SETTLE_AUDIT)
                    || subject.isPermitted(CcConstants.PERM_FREIGHT_SETTLE_REVIEW)
                    || subject.isPermitted(CcConstants.PERM_FREIGHT_CONTRACT_MANAGE);
        } catch (Exception e) {
            return false;
        }
    }

    private FreightContract requireEffectiveContract(Long contractId) {
        FreightContract contract = contractService.requireContract(contractId);
        if (!CcConstants.EFFECTIVE.equals(contract.getStatus())) {
            throw new BusinessException("只能按生效（EFFECTIVE）合同版本计算费用，合同 V"
                    + contract.getVersionNo() + " 当前状态=" + contract.getStatus());
        }
        return contract;
    }

    private TransportBatch requireSignedBatch(Long batchId) {
        TransportBatch batch = batchId == null ? null : batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        if (!CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            throw new BusinessException("仅已签收闭环批次可以预结算运费，批次 "
                    + batch.getBatchNo() + " 当前状态: " + batch.getStatus());
        }
        return batch;
    }

    private FreightSettleItem requireItem(Long settlementId, Long itemId) {
        FreightSettleItem item = itemId == null ? null : itemMapper.selectById(itemId);
        if (item == null || !settlementId.equals(item.getSettlementId())) {
            throw new BusinessException("费用项不存在或不属于结算单 " + settlementId + ": " + itemId);
        }
        return item;
    }

    private void requireNotAudited(FreightSettlement settlement, String action) {
        if (CcConstants.FST_AUDITED.equals(settlement.getStatus())) {
            throw new BusinessException("结算单 " + settlement.getSettlementNo()
                    + " 已财务审核冻结，金额与争议结论不可变更，不能" + action);
        }
    }

    /** 按费用项 included 标志重算结算金额与状态 */
    private void recalculate(FreightSettlement settlement) {
        List<FreightSettleItem> items = listItems(settlement.getId());
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal penalty = BigDecimal.ZERO;
        int pending = 0;
        for (FreightSettleItem item : items) {
            if (!Boolean.TRUE.equals(item.getIncluded())) {
                if (CcConstants.DISPUTE_PENDING.equals(item.getDisputeStatus())) {
                    pending++;
                }
                continue;
            }
            if (CcConstants.FEE_BASE.equals(item.getItemKind())) {
                base = base.add(item.getAmount());
            } else {
                penalty = penalty.add(item.getAmount());
            }
            if (CcConstants.DISPUTE_PENDING.equals(item.getDisputeStatus())) {
                pending++;
            }
        }
        settlement.setBaseAmount(PriceMath.money(base));
        settlement.setPenaltyAmount(PriceMath.money(penalty));
        settlement.setPayableAmount(PriceMath.money(base.subtract(penalty)));
        settlement.setDisputePendingCount(pending);
        settlement.setStatus(pending > 0 ? CcConstants.FST_DISPUTED : CcConstants.FST_PENDING);
        settlementMapper.updateById(settlement);
    }

    private String buildRuleSnapshot(FreightContractService.ContractRules rules,
                                     FreightPricingEngine.CalcResult calc) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("contractNo", FreightContractService.CONTRACT_NO);
        snap.put("versionNo", rules.contract.getVersionNo());
        snap.put("versionName", rules.contract.getVersionName());
        snap.put("boxType", calc.boxType);
        snap.put("mileageKm", calc.mileage);
        snap.put("baseRates", rules.baseRates);
        snap.put("penaltyRules", rules.penaltyRules);
        try {
            return M.writeValueAsString(snap);
        } catch (Exception e) {
            throw new BusinessException("合同规则快照序列化失败: " + e.getMessage());
        }
    }

    private String carrierName(TransportBatch batch) {
        if (batch.getLockedPriceId() == null) {
            return null;
        }
        com.evops.coldchain.entity.LockedPrice locked =
                lockedPriceMapper.selectById(batch.getLockedPriceId());
        return locked == null ? null : locked.getCarrierNameSnap();
    }

    // —— 岗位字段收窄 ——

    private Map<String, Object> maskSettlement(FreightSettlement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("settlementNo", s.getSettlementNo());
        m.put("batchId", s.getBatchId());
        m.put("batchNo", s.getBatchNo());
        m.put("carrierNameSnap", s.getCarrierNameSnap());
        m.put("boxTypeSnap", s.getBoxTypeSnap());
        m.put("boxTypeNameSnap", s.getBoxTypeNameSnap());
        m.put("mileageKmSnap", s.getMileageKmSnap());
        m.put("mileageSource", s.getMileageSource());
        m.put("contractVersionNo", s.getContractVersionNo());
        // 运营可见金额（对账所需），但不可见费率/单价/规则快照与财务审核人信息
        m.put("baseAmount", s.getBaseAmount());
        m.put("penaltyAmount", s.getPenaltyAmount());
        m.put("payableAmount", s.getPayableAmount());
        m.put("currency", s.getCurrency());
        m.put("status", s.getStatus());
        m.put("generateTime", s.getGenerateTime());
        m.put("generateByName", s.getGenerateByName());
        m.put("disputePendingCount", s.getDisputePendingCount());
        m.put("auditTime", s.getAuditTime());
        // unitPrice/ruleSnapshot/auditBy/auditRemark 对运营收窄
        m.put("ruleSnapshot", null);
        m.put("auditBy", null);
        m.put("auditByName", null);
        m.put("auditRemark", null);
        return m;
    }

    private Map<String, Object> maskItem(FreightSettleItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("itemSeq", item.getItemSeq());
        m.put("itemKind", item.getItemKind());
        m.put("title", item.getTitle());
        m.put("exceptionId", item.getExceptionId());
        m.put("exceptionType", item.getExceptionType());
        m.put("exceptionTypeName", item.getExceptionTypeName());
        m.put("gradeSnap", item.getGradeSnap());
        m.put("calcType", item.getCalcType());
        m.put("amount", item.getAmount());
        m.put("included", item.getIncluded());
        m.put("basisBusiness", item.getBasisBusiness());
        m.put("disputeStatus", item.getDisputeStatus());
        m.put("disputeReason", item.getDisputeReason());
        m.put("disputeByName", item.getDisputeByName());
        m.put("disputeTime", item.getDisputeTime());
        m.put("reviewTime", item.getReviewTime());
        m.put("reviewNote", item.getReviewNote());
        // 单价/计价依据/规则快照/复核人账号仅财务可见
        m.put("unitPrice", null);
        m.put("quantity", null);
        m.put("basisPricing", null);
        m.put("ruleSnapshot", null);
        m.put("reviewByName", null);
        m.put("reviewBy", null);
        return m;
    }

    private Map<String, Object> settlementEntityToMap(FreightSettlement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("settlementNo", s.getSettlementNo());
        m.put("batchId", s.getBatchId());
        m.put("batchNo", s.getBatchNo());
        m.put("carrierNameSnap", s.getCarrierNameSnap());
        m.put("boxTypeSnap", s.getBoxTypeSnap());
        m.put("boxTypeNameSnap", s.getBoxTypeNameSnap());
        m.put("mileageKmSnap", s.getMileageKmSnap());
        m.put("mileageSource", s.getMileageSource());
        m.put("contractId", s.getContractId());
        m.put("contractVersionNo", s.getContractVersionNo());
        m.put("ruleSnapshot", s.getRuleSnapshot());
        m.put("baseAmount", s.getBaseAmount());
        m.put("penaltyAmount", s.getPenaltyAmount());
        m.put("payableAmount", s.getPayableAmount());
        m.put("currency", s.getCurrency());
        m.put("status", s.getStatus());
        m.put("generateTime", s.getGenerateTime());
        m.put("generateBy", s.getGenerateBy());
        m.put("generateByName", s.getGenerateByName());
        m.put("disputePendingCount", s.getDisputePendingCount());
        m.put("auditTime", s.getAuditTime());
        m.put("auditBy", s.getAuditBy());
        m.put("auditByName", s.getAuditByName());
        m.put("auditRemark", s.getAuditRemark());
        m.put("createTime", s.getCreateTime());
        return m;
    }

    private Map<String, Object> itemEntityToMap(FreightSettleItem item) {        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("itemSeq", item.getItemSeq());
        m.put("itemKind", item.getItemKind());
        m.put("title", item.getTitle());
        m.put("exceptionId", item.getExceptionId());
        m.put("exceptionType", item.getExceptionType());
        m.put("exceptionTypeName", item.getExceptionTypeName());
        m.put("gradeSnap", item.getGradeSnap());
        m.put("calcType", item.getCalcType());
        m.put("unitPrice", item.getUnitPrice());
        m.put("quantity", item.getQuantity());
        m.put("amount", item.getAmount());
        m.put("included", item.getIncluded());
        m.put("basisBusiness", item.getBasisBusiness());
        m.put("basisPricing", item.getBasisPricing());
        m.put("ruleSnapshot", item.getRuleSnapshot());
        m.put("disputeStatus", item.getDisputeStatus());
        m.put("disputeReason", item.getDisputeReason());
        m.put("disputeBy", item.getDisputeBy());
        m.put("disputeByName", item.getDisputeByName());
        m.put("disputeTime", item.getDisputeTime());
        m.put("reviewBy", item.getReviewBy());
        m.put("reviewByName", item.getReviewByName());
        m.put("reviewTime", item.getReviewTime());
        m.put("reviewNote", item.getReviewNote());
        return m;
    }
}
