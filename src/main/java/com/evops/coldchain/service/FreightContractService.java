package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.BaseRateItem;
import com.evops.coldchain.dto.ContractDraftRequest;
import com.evops.coldchain.dto.PenaltyRuleItem;
import com.evops.coldchain.entity.FreightBaseRate;
import com.evops.coldchain.entity.FreightContract;
import com.evops.coldchain.entity.FreightPenaltyRule;
import com.evops.coldchain.mapper.FreightBaseRateMapper;
import com.evops.coldchain.mapper.FreightContractMapper;
import com.evops.coldchain.mapper.FreightPenaltyRuleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 运输费用合同版本（V14）。
 *
 * 版本只追加：草稿 DRAFT 保存整版费率档与扣罚规则；发布把同合同号当前生效版本置
 * SUPERSEDED，新版本置 EFFECTIVE。新版本只影响此后生成的预结算与回放，
 * 已生成结算单持有规则快照，与合同后续演进脱钩（与档案/路线/分级规则快照同一口径）。
 */
@Service
public class FreightContractService {

    public static final String CONTRACT_NO = "CC-FREIGHT-MASTER";

    @Resource
    private FreightContractMapper contractMapper;
    @Resource
    private FreightBaseRateMapper rateMapper;
    @Resource
    private FreightPenaltyRuleMapper penaltyMapper;
    @Resource
    private OpLogService opLogService;

    /** 一个合同版本的完整规则（计算引擎与结算快照共用） */
    public static final class ContractRules {
        public final FreightContract contract;
        public final List<FreightBaseRate> baseRates;
        public final List<FreightPenaltyRule> penaltyRules;

        ContractRules(FreightContract contract, List<FreightBaseRate> baseRates,
                      List<FreightPenaltyRule> penaltyRules) {
            this.contract = contract;
            this.baseRates = baseRates;
            this.penaltyRules = penaltyRules;
        }
    }

    public List<FreightContract> listVersions() {
        return contractMapper.selectList(new QueryWrapper<FreightContract>()
                .eq("contract_no", CONTRACT_NO).orderByAsc("version_no"));
    }

    public FreightContract requireContract(Long contractId) {
        FreightContract contract = contractId == null ? null : contractMapper.selectById(contractId);
        if (contract == null) {
            throw new BusinessException("费用合同版本不存在: " + contractId);
        }
        return contract;
    }

    public FreightContract effectiveContract() {
        FreightContract contract = contractMapper.selectOne(new QueryWrapper<FreightContract>()
                .eq("contract_no", CONTRACT_NO)
                .eq("status", CcConstants.EFFECTIVE)
                .orderByDesc("version_no").last("LIMIT 1"));
        if (contract == null) {
            throw new BusinessException("当前没有生效的运输费用合同版本，无法计算费用");
        }
        return contract;
    }

    /** 取某版本的完整规则（费率档 + 扣罚规则） */
    public ContractRules rulesOf(Long contractId) {
        FreightContract contract = requireContract(contractId);
        List<FreightBaseRate> rates = rateMapper.selectList(new QueryWrapper<FreightBaseRate>()
                .eq("contract_id", contractId).eq("enabled", true).orderByAsc("box_type", "km_from"));
        List<FreightPenaltyRule> penalties = penaltyMapper.selectList(new QueryWrapper<FreightPenaltyRule>()
                .eq("contract_id", contractId).eq("enabled", true).orderByAsc("id"));
        return new ContractRules(contract, rates, penalties);
    }

    /** 起草新版本（DRAFT）：整版费率档与扣罚规则随版本一起落库 */
    @Transactional
    public FreightContract draft(ContractDraftRequest req) {
        String name = req.getVersionName().trim();
        Integer nextNo = nextVersionNo();
        List<BaseRateItem> rateItems = req.getBaseRates() == null
                ? Collections.emptyList() : req.getBaseRates();
        List<PenaltyRuleItem> penaltyItems = req.getPenaltyRules() == null
                ? Collections.emptyList() : req.getPenaltyRules();
        if (rateItems.isEmpty()) {
            throw new BusinessException("新合同版本至少要配置一个箱型的基础运费费率档");
        }
        validateRates(rateItems);
        validatePenalties(penaltyItems);

        FreightContract contract = new FreightContract();
        contract.setContractNo(CONTRACT_NO);
        contract.setVersionNo(nextNo);
        contract.setVersionName(name);
        contract.setStatus(CcConstants.FC_DRAFT);
        contract.setChangeReason(req.getChangeReason());
        contract.setRemark(req.getRemark());
        contractMapper.insert(contract);

        for (BaseRateItem r : rateItems) {
            FreightBaseRate rate = new FreightBaseRate();
            rate.setContractId(contract.getId());
            rate.setBoxType(r.getBoxType().trim().toUpperCase());
            rate.setBoxTypeName(QuoteDimensions.boxTypeName(rate.getBoxType()));
            rate.setKmFrom(PriceMath.money(r.getKmFrom()));
            rate.setKmTo(r.getKmTo() == null ? null : PriceMath.money(r.getKmTo()));
            rate.setPricePerKm(PriceMath.money(r.getPricePerKm()));
            rate.setMinCharge(PriceMath.money(r.getMinCharge() == null ? BigDecimal.ZERO : r.getMinCharge()));
            rate.setEnabled(true);
            rateMapper.insert(rate);
        }
        for (PenaltyRuleItem p : penaltyItems) {
            FreightPenaltyRule rule = new FreightPenaltyRule();
            rule.setContractId(contract.getId());
            String type = p.getExceptionType().trim().toUpperCase();
            rule.setExceptionType(type);
            rule.setExceptionTypeName(penaltyTypeName(type));
            rule.setGrade(p.getGrade() == null || p.getGrade().trim().isEmpty()
                    ? null : p.getGrade().trim().toUpperCase());
            String calc = p.getCalcType().trim().toUpperCase();
            rule.setCalcType(calc);
            if (CcConstants.FEE_CALC_FIXED.equals(calc)) {
                rule.setFixedAmount(PriceMath.money(p.getFixedAmount()));
            } else {
                rule.setPercent(p.getPercent().setScale(4, java.math.RoundingMode.HALF_UP));
                rule.setCapAmount(p.getCapAmount() == null ? null : PriceMath.money(p.getCapAmount()));
            }
            rule.setEnabled(true);
            rule.setRemark(p.getRemark());
            penaltyMapper.insert(rule);
        }

        opLogService.log(CcConstants.ENT_FREIGHT_CONTRACT, contract.getId(), null,
                CcConstants.ACT_FREIGHT_DRAFT,
                String.format("费用合同 %s V%d「%s」起草：基础费率档%d条、异常扣罚规则%d条；操作者=%s（草稿未生效，不影响任何已生成结算）",
                        CONTRACT_NO, nextNo, name, rateItems.size(), penaltyItems.size(),
                        UserContext.currentName()));
        return contract;
    }

    /** 发布新版本：热替换当前生效版本，只影响此后生成的预结算/回放 */
    @Transactional
    public FreightContract publish(Long contractId) {
        FreightContract contract = requireContract(contractId);
        if (!CcConstants.FC_DRAFT.equals(contract.getStatus())) {
            throw new BusinessException("仅草稿（DRAFT）合同版本可以发布，当前状态: " + contract.getStatus());
        }
        FreightContract current = contractMapper.selectOne(new QueryWrapper<FreightContract>()
                .eq("contract_no", CONTRACT_NO)
                .eq("status", CcConstants.EFFECTIVE)
                .orderByDesc("version_no").last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (current != null) {
            current.setStatus(CcConstants.SUPERSEDED);
            current.setUpdateTime(now);
            contractMapper.updateById(current);
        }
        contract.setStatus(CcConstants.EFFECTIVE);
        contract.setEffectiveFrom(now);
        contract.setPublishTime(now);
        contract.setPublishBy(UserContext.currentId());
        contract.setPublishByName(UserContext.currentName());
        contractMapper.updateById(contract);

        opLogService.log(CcConstants.ENT_FREIGHT_CONTRACT, contract.getId(), null,
                CcConstants.ACT_FREIGHT_PUBLISH,
                String.format("费用合同 %s V%d「%s」发布生效%s；操作者=%s。只影响此后生成的预结算，已审核结算冻结不重算",
                        CONTRACT_NO, contract.getVersionNo(), contract.getVersionName(),
                        current == null ? "" : "，V" + current.getVersionNo() + " 置 SUPERSEDED",
                        UserContext.currentName()));
        return contract;
    }

    private Integer nextVersionNo() {
        List<FreightContract> all = contractMapper.selectList(new QueryWrapper<FreightContract>()
                .eq("contract_no", CONTRACT_NO).orderByDesc("version_no").last("LIMIT 1"));
        return all.isEmpty() ? 1 : all.get(0).getVersionNo() + 1;
    }

    private void validateRates(List<BaseRateItem> items) {
        Set<String> boxTypes = new HashSet<>();
        for (BaseRateItem r : items) {
            if (r.getBoxType() == null || r.getBoxType().trim().isEmpty()) {
                throw new BusinessException("基础费率档箱型代码不能为空");
            }
            String box = r.getBoxType().trim().toUpperCase();
            QuoteDimensions.boxTypeName(box); // 未知箱型直接拒绝
            boxTypes.add(box);
            if (r.getKmFrom() == null || r.getPricePerKm() == null) {
                throw new BusinessException("费率档里程起点与单价不能为空（箱型=" + box + "）");
            }
            if (r.getKmFrom().signum() < 0 || r.getPricePerKm().signum() < 0) {
                throw new BusinessException("费率档里程起点不能为负、单价不能为负（箱型=" + box + "）");
            }
            if (r.getKmTo() != null && r.getKmTo().compareTo(r.getKmFrom()) <= 0) {
                throw new BusinessException("费率档里程区间终点必须大于起点（箱型=" + box + "）");
            }
        }
        // 同箱型区间不得重叠
        for (String box : boxTypes) {
            List<BaseRateItem> sorted = new ArrayList<>();
            for (BaseRateItem r : items) {
                if (box.equals(r.getBoxType().trim().toUpperCase())) {
                    sorted.add(r);
                }
            }
            sorted.sort(Comparator.comparing(BaseRateItem::getKmFrom));
            for (int i = 1; i < sorted.size(); i++) {
                BigDecimal prevTo = sorted.get(i - 1).getKmTo();
                if (prevTo == null || sorted.get(i).getKmFrom().compareTo(prevTo) < 0) {
                    throw new BusinessException("箱型[" + box + "]费率档里程区间重叠或区间顺序错误");
                }
            }
        }
    }

    private void validatePenalties(List<PenaltyRuleItem> items) {
        Set<String> exactKeys = new HashSet<>();
        Set<String> wildcardTypes = new HashSet<>();
        for (PenaltyRuleItem p : items) {
            if (p.getExceptionType() == null) {
                throw new BusinessException("扣罚规则异常类型不能为空");
            }
            String type = p.getExceptionType().trim().toUpperCase();
            if (!CcConstants.PENALTY_TYPES.contains(type)) {
                throw new BusinessException("异常扣罚仅支持 延误/温度超限/封签破损: " + type);
            }
            String grade = p.getGrade() == null || p.getGrade().trim().isEmpty()
                    ? null : p.getGrade().trim().toUpperCase();
            if (grade != null && !CcConstants.GRADE_L1.equals(grade)
                    && !CcConstants.GRADE_L2.equals(grade) && !CcConstants.GRADE_L3.equals(grade)) {
                throw new BusinessException("扣罚规则等级仅支持 L1/L2/L3: " + grade);
            }
            if (CcConstants.FEE_CALC_FIXED.equals(p.getCalcType() == null ? null : p.getCalcType().trim().toUpperCase())) {
                if (p.getFixedAmount() == null || p.getFixedAmount().signum() < 0) {
                    throw new BusinessException("定额扣罚金额必须为不小于0的数值: " + type);
                }
            } else if (CcConstants.FEE_CALC_PERCENT.equals(p.getCalcType() == null ? null : p.getCalcType().trim().toUpperCase())) {
                if (p.getPercent() == null || p.getPercent().signum() < 0) {
                    throw new BusinessException("比例扣罚百分比必须为不小于0的数值: " + type);
                }
            } else {
                throw new BusinessException("扣罚计价方式仅支持 FIXED 定额 / PERCENT 按比例");
            }
            if (grade == null) {
                if (!wildcardTypes.add(type)) {
                    throw new BusinessException("异常类型[" + penaltyTypeName(type) + "]只能配置一条通配等级扣罚规则");
                }
            } else if (!exactKeys.add(type + "#" + grade)) {
                throw new BusinessException("异常类型[" + penaltyTypeName(type) + "]等级 " + grade + " 的扣罚规则重复");
            }
        }
    }

    /** 三类扣罚异常中文名（与分级规则种子同口径） */
    public static String penaltyTypeName(String type) {
        switch (type) {
            case CcConstants.EXC_DELAY: return "运输延误";
            case CcConstants.EXC_TEMP: return "温度超限";
            case CcConstants.EXC_SEAL_BROKEN: return "封签破损";
            default: return type;
        }
    }
}
