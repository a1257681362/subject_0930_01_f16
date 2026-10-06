package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.FreightBaseRate;
import com.evops.coldchain.entity.FreightPenaltyRule;
import com.evops.coldchain.entity.LockedPrice;
import com.evops.coldchain.entity.Route;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.LockedPriceMapper;
import com.evops.coldchain.mapper.RouteMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 运输费用计算引擎（V14）。纯计算、不落库：给定批次、某合同版本完整规则与可选覆盖值，
 * 产出每项费用（基础运费一项 + 每条已关闭异常扣罚一项）及其业务依据/计价依据。
 *
 * 口径：
 * - 箱型：优先覆盖值，其次批次锁价单快照（{@link LockedPrice#getBoxTypeSnap()}）。
 * - 里程：优先覆盖值；有路线取路线 total_km；无路线又无覆盖值直接拒绝（必须人工补录里程）。
 * - 基础运费 = 单价 × 里程，再与该档最低收费取大。
 * - 扣罚仅对 RESOLVED 已复核关闭异常生成（未关闭异常不预扣，由生成卡口拦截结算）；
 *   同一异常类型先匹配精确等级规则，再匹配通配等级规则，都没有则不扣罚。
 * - PERCENT 扣罚 = 基础运费 × 百分比/100，封顶取小；金额四舍五入 2 位小数。
 */
@Component
public class FreightPricingEngine {

    @Resource
    private LockedPriceMapper lockedPriceMapper;
    @Resource
    private RouteMapper routeMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;

    /** 单个费用项（瞬态，不落库；结算服务把它逐行复制为 t_cc_freight_settle_item） */
    public static final class CalcItem {
        public String itemKind;
        public String title;
        public Long exceptionId;
        public String exceptionType;
        public String exceptionTypeName;
        public String gradeSnap;
        public String calcType;
        public BigDecimal unitPrice;
        public BigDecimal quantity;
        public BigDecimal amount;
        public String basisBusiness;
        public String basisPricing;
        public String ruleSnapshot;
    }

    /** 一次计算的完整结果 */
    public static final class CalcResult {
        public String boxType;
        public String boxTypeName;
        public BigDecimal mileage;
        public String mileageSource;
        public List<CalcItem> items = new ArrayList<>();
        public BigDecimal baseAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal penaltyAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public BigDecimal payableAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        public long unclosedCount;

        public CalcItem baseItem() {
            for (CalcItem it : items) {
                if (CcConstants.FEE_BASE.equals(it.itemKind)) {
                    return it;
                }
            }
            return null;
        }
    }

    /**
     * 计算批次在指定合同版本规则下的全部费用。
     *
     * @param overrideBoxType 覆盖箱型（可空）
     * @param overrideMileage 覆盖里程（可空；无路线批次必填）
     */
    public CalcResult calculate(TransportBatch batch, FreightContractService.ContractRules rules,
                                String overrideBoxType, BigDecimal overrideMileage) {
        CalcResult result = new CalcResult();

        // 1) 箱型
        String boxType = overrideBoxType == null || overrideBoxType.trim().isEmpty()
                ? null : overrideBoxType.trim().toUpperCase();
        String carrierName = null;
        if (boxType == null && batch.getLockedPriceId() != null) {
            LockedPrice locked = lockedPriceMapper.selectById(batch.getLockedPriceId());
            if (locked != null) {
                boxType = locked.getBoxTypeSnap();
                carrierName = locked.getCarrierNameSnap();
            }
        }
        if (boxType == null) {
            throw new BusinessException("批次 " + batch.getBatchNo()
                    + " 未引用锁价单且未指定箱型，无法按箱型计算基础运费，请补录箱型");
        }
        QuoteDimensions.boxTypeName(boxType); // 未知箱型拒绝
        result.boxType = boxType;
        result.boxTypeName = QuoteDimensions.boxTypeName(boxType);

        // 2) 里程
        BigDecimal mileage;
        String mileageSource;
        if (overrideMileage != null && overrideMileage.signum() >= 0) {
            mileage = PriceMath.money(overrideMileage);
            mileageSource = CcConstants.MILEAGE_MANUAL;
        } else if (batch.getRouteId() != null) {
            Route route = routeMapper.selectById(batch.getRouteId());
            if (route == null || route.getTotalKm() == null) {
                throw new BusinessException("批次 " + batch.getBatchNo() + " 引用的运输路线缺少总里程，请人工补录里程");
            }
            mileage = PriceMath.money(route.getTotalKm());
            mileageSource = CcConstants.MILEAGE_ROUTE;
        } else {
            throw new BusinessException("批次 " + batch.getBatchNo()
                    + " 无运输路线里程，必须人工补录计费里程后才能计算基础运费");
        }
        result.mileage = mileage;
        result.mileageSource = mileageSource;

        // 3) 基础运费：命中箱型 × 里程区间费率档
        FreightBaseRate hitRate = null;
        for (FreightBaseRate rate : rules.baseRates) {
            if (!boxType.equals(rate.getBoxType())) {
                continue;
            }
            boolean geFrom = mileage.compareTo(rate.getKmFrom()) >= 0;
            boolean ltTo = rate.getKmTo() == null || mileage.compareTo(rate.getKmTo()) < 0;
            if (geFrom && ltTo) {
                hitRate = rate;
                break;
            }
        }
        if (hitRate == null) {
            throw new BusinessException(String.format(
                    "合同 V%d 未配置箱型[%s]在 %s 公里里程档的基础费率，无法计算基础运费",
                    rules.contract.getVersionNo(), result.boxTypeName, mileage));
        }
        BigDecimal rawBase = PriceMath.money(hitRate.getPricePerKm().multiply(mileage));
        BigDecimal baseAmount = rawBase.max(PriceMath.money(hitRate.getMinCharge()));
        result.baseAmount = baseAmount;

        CalcItem base = new CalcItem();
        base.itemKind = CcConstants.FEE_BASE;
        base.title = "基础运费（" + result.boxTypeName + "）";
        base.calcType = CcConstants.FEE_CALC_RATE;
        base.unitPrice = hitRate.getPricePerKm();
        base.quantity = mileage;
        base.amount = baseAmount;
        String interval = hitRate.getKmTo() == null
                ? hitRate.getKmFrom().stripTrailingZeros().toPlainString() + "km 及以上"
                : hitRate.getKmFrom().stripTrailingZeros().toPlainString() + "（含）~"
                + hitRate.getKmTo().stripTrailingZeros().toPlainString() + "km";
        base.basisBusiness = String.format(
                "承运商=%s；箱型=%s（%s）；计费里程=%s公里（来源=%s）",
                carrierName == null ? "未引用锁价单" : carrierName,
                result.boxTypeName, boxType,
                mileage.stripTrailingZeros().toPlainString(),
                CcConstants.MILEAGE_ROUTE.equals(mileageSource) ? "运输路线总里程" : "人工补录");
        base.basisPricing = String.format(
                "合同 V%d 命中里程档[%s]：单价 %s 元/公里 × %s 公里 = %s 元；与该档最低收费 %s 元取大 = %s 元",
                rules.contract.getVersionNo(), interval,
                hitRate.getPricePerKm().toPlainString(), mileage.stripTrailingZeros().toPlainString(),
                rawBase.toPlainString(), hitRate.getMinCharge().toPlainString(), baseAmount.toPlainString());
        base.ruleSnapshot = String.format(
                "{contractVersion:%d,boxType:%s,kmFrom:%s,kmTo:%s,pricePerKm:%s,minCharge:%s}",
                rules.contract.getVersionNo(), boxType,
                hitRate.getKmFrom().toPlainString(),
                hitRate.getKmTo() == null ? "null" : hitRate.getKmTo().toPlainString(),
                hitRate.getPricePerKm().toPlainString(), hitRate.getMinCharge().toPlainString());
        result.items.add(base);

        // 4) 已关闭异常逐单生成扣罚项（未关闭异常不预扣）
        List<ExceptionEvent> exceptions = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("batch_id", batch.getId())
                .in("exception_type", CcConstants.PENALTY_TYPES)
                .orderByAsc("id"));
        long unclosed = 0;
        int seq = 1;
        for (ExceptionEvent event : exceptions) {
            if (!CcConstants.EXC_RESOLVED.equals(event.getStatus())) {
                unclosed++;
                continue;
            }
            FreightPenaltyRule rule = matchRule(rules.penaltyRules, event.getExceptionType(), event.getGrade());
            if (rule == null) {
                // 合同版本未约定该异常扣罚：不出费用项（与“无扣罚即零”一致，回放差异中体现为规则新增）
                continue;
            }
            CalcItem item = buildPenalty(seq++, event, rule, baseAmount, rules.contract.getVersionNo());
            result.items.add(item);
            result.penaltyAmount = PriceMath.money(result.penaltyAmount.add(item.amount));
        }
        result.unclosedCount = unclosed;
        result.payableAmount = PriceMath.money(result.baseAmount.subtract(result.penaltyAmount));
        return result;
    }

    /** 精确等级规则优先，其次通配（grade 为空）规则 */
    private FreightPenaltyRule matchRule(List<FreightPenaltyRule> rules, String type, String grade) {
        FreightPenaltyRule wildcard = null;
        for (FreightPenaltyRule rule : rules) {
            if (!type.equals(rule.getExceptionType())) {
                continue;
            }
            if (rule.getGrade() == null) {
                wildcard = rule;
            } else if (rule.getGrade().equals(grade)) {
                return rule;
            }
        }
        return wildcard;
    }

    private CalcItem buildPenalty(int seq, ExceptionEvent event, FreightPenaltyRule rule,
                                  BigDecimal baseAmount, int contractVersionNo) {
        CalcItem item = new CalcItem();
        item.itemKind = CcConstants.FEE_PENALTY;
        String typeName = FreightContractService.penaltyTypeName(event.getExceptionType());
        item.title = String.format("扣罚%d：%s（%s，异常单#%d）",
                seq, typeName, event.getGrade() == null ? "未定级" : event.getGrade(), event.getId());
        item.exceptionId = event.getId();
        item.exceptionType = event.getExceptionType();
        item.exceptionTypeName = typeName;
        item.gradeSnap = event.getGrade();

        if (CcConstants.FEE_CALC_FIXED.equals(rule.getCalcType())) {
            item.calcType = CcConstants.FEE_CALC_FIXED;
            item.unitPrice = rule.getFixedAmount();
            item.quantity = BigDecimal.ONE;
            item.amount = PriceMath.money(rule.getFixedAmount());
            item.basisPricing = String.format(
                    "合同 V%d 扣罚规则：%s/%s 定额扣罚 %s 元",
                    contractVersionNo, typeName, item.gradeSnap, item.amount.toPlainString());
        } else {
            item.calcType = CcConstants.FEE_CALC_PERCENT;
            BigDecimal raw = PriceMath.money(baseAmount.multiply(rule.getPercent())
                    .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP));
            BigDecimal amount = rule.getCapAmount() == null ? raw : raw.min(PriceMath.money(rule.getCapAmount()));
            item.unitPrice = rule.getPercent();
            item.quantity = baseAmount;
            item.amount = amount;
            item.basisPricing = String.format(
                    "合同 V%d 扣罚规则：%s/%s 按基础运费 %s%% 计 = %s × %s%% = %s 元%s",
                    contractVersionNo, typeName, item.gradeSnap,
                    rule.getPercent().stripTrailingZeros().toPlainString(),
                    baseAmount.toPlainString(), rule.getPercent().stripTrailingZeros().toPlainString(),
                    raw.toPlainString(),
                    rule.getCapAmount() == null ? ""
                            : "，封顶 " + rule.getCapAmount().toPlainString() + " 元取小 = " + amount.toPlainString() + " 元");
        }
        item.basisBusiness = String.format(
                "异常单#%d（%s，等级=%s），首次发现=%s，确认=%s，已由 %s 复核关闭（复核状态=%s）；描述：%s",
                event.getId(), typeName, item.gradeSnap,
                event.getFoundTime(), event.getConfirmTime(),
                event.getReviewBy() == null ? "质量岗" : ("账号#" + event.getReviewBy()),
                event.getReviewStatus(),
                event.getDescription() == null ? "" : event.getDescription());
        item.ruleSnapshot = String.format(
                "{contractVersion:%d,exceptionType:%s,grade:%s,calcType:%s,fixed:%s,percent:%s,cap:%s}",
                contractVersionNo, event.getExceptionType(),
                item.gradeSnap == null ? "null" : item.gradeSnap, rule.getCalcType(),
                rule.getFixedAmount() == null ? "null" : rule.getFixedAmount().toPlainString(),
                rule.getPercent() == null ? "null" : rule.getPercent().toPlainString(),
                rule.getCapAmount() == null ? "null" : rule.getCapAmount().toPlainString());
        return item;
    }
}
