package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.ReturnBatchMapper;
import com.evops.coldchain.mapper.TempAlertMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监管报表指标计算（V16）：对一个统计月 [start,end) 汇总四项指标。
 *
 * 核心原则——区分“真零”与“待补”，绝不静默填零：
 * - 源记录在该月为 0 行（整月无发运/无超温预警/无异常/无退货）→ 指标记 0（真零，数据齐备）。
 * - 源记录存在但关键字段缺失，导致指标无法完整汇总 → 该指标置空并在 {@link MetricBundle#pendingReasons}
 *   登记具体缺失对象（批次号/预警#/异常#），由生成服务整体标记 PENDING_SUPPLEMENT。
 *
 * 归属口径（与统计口径版本字段对应）：
 * - 发运量：批次 ship_time 落在月内（批次数 + ship_qty 合计）。
 * - 超温时长：温控预警 start_time 落在月内，取 duration_minutes 合计（分钟）。
 * - 异常关闭率：异常归属时刻（occurred_time 优先，空则 found_time）落在月内；
 *   已复核关闭（status=RESOLVED）为分子。closeWindowDays=0 不限制关闭期限（生成时点已关闭即计分子）；
 *   &gt;0 时须在归属时刻后 N 日内关闭。
 * - 退货数量：退货批次 create_time（登记时刻）落在月内，return_qty 合计。
 */
@Service
public class RegReportComputeService {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private TempAlertMapper alertMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private ReturnBatchMapper returnBatchMapper;

    /** 单月四项指标计算结果（未落库）；pendingReasons 非空的指标视为待补 */
    public static class MetricBundle {
        Integer shippedBatchCount;
        Integer shipQtyTotal;
        Long overtempMinutes;
        Integer exceptionTotalCount;
        Integer exceptionClosedCount;
        BigDecimal exceptionCloseRate;
        Integer returnQtyTotal;
        /** 指标键 -> 缺失原因（有序；非空即该指标待补） */
        final Map<String, String> pendingReasons = new LinkedHashMap<>();

        public boolean isPending() {
            return !pendingReasons.isEmpty();
        }
    }

    public MetricBundle compute(LocalDateTime start, LocalDateTime end, int closeWindowDays) {
        MetricBundle m = new MetricBundle();
        computeShipment(m, start, end);
        computeOvertemp(m, start, end);
        computeExceptionCloseRate(m, start, end, closeWindowDays);
        computeReturn(m, start, end);
        return m;
    }

    /** 发运量：月内发运批次数与发运数量；任一发运批次缺发运数量 -> 指标待补（不静默按 0 累加） */
    private void computeShipment(MetricBundle m, LocalDateTime start, LocalDateTime end) {
        List<TransportBatch> shipped = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .isNotNull("ship_time")
                .ge("ship_time", start).lt("ship_time", end)
                .orderByAsc("id"));
        if (shipped.isEmpty()) {
            // 整月无发运：真零，数据齐备
            m.shippedBatchCount = 0;
            m.shipQtyTotal = 0;
            return;
        }
        List<String> missingQty = new ArrayList<>();
        int qty = 0;
        for (TransportBatch b : shipped) {
            if (b.getShipQty() == null) {
                missingQty.add(b.getBatchNo() == null ? ("批次#" + b.getId()) : b.getBatchNo());
            } else {
                qty += b.getShipQty();
            }
        }
        if (!missingQty.isEmpty()) {
            // 存在已发运但发运数量缺失的批次：批次数虽可数，但发运量无法完整汇总，整项标待补
            m.pendingReasons.put(CcConstants.REG_METRIC_SHIPMENT,
                    "月内 " + shipped.size() + " 批发运中 " + missingQty.size()
                            + " 批缺少发运数量（" + String.join("、", missingQty) + "），发运量无法完整汇总");
            return;
        }
        m.shippedBatchCount = shipped.size();
        m.shipQtyTotal = qty;
    }

    /**
     * 超温时长（分钟）：月内触发的温控预警连续超限时长合计。
     * 关键数据完整性校验——当月若存在“未挂温控预警（alert_id 为空）的温度超限异常”，说明确有超温发生、
     * 却没有设备连续超限时长可计，此时仅汇总预警时长会漏计甚至静默成 0，故整项标待补。
     * 任一预警本身缺时长（防御性，正常流程预警时长非空）同样标待补。
     */
    private void computeOvertemp(MetricBundle m, LocalDateTime start, LocalDateTime end) {
        // 1) 月内温度超限异常中，未由温控预警背书（无连续超限时长）的部分
        List<String> unbacked = new ArrayList<>();
        for (ExceptionEvent e : loadExceptionByType(CcConstants.EXC_TEMP, start, end)) {
            if (e.getAlertId() == null) {
                unbacked.add("异常#" + e.getId());
            }
        }
        if (!unbacked.isEmpty()) {
            m.pendingReasons.put(CcConstants.REG_METRIC_OVERTEMP,
                    "月内 " + unbacked.size() + " 起温度超限异常无温控预警连续超限时长背书（"
                            + String.join("、", unbacked)
                            + "），超温时长无法完整汇总，按待补处理而非按 0 计");
            return;
        }

        // 2) 月内触发预警的时长合计
        List<TempAlert> alerts = alertMapper.selectList(new QueryWrapper<TempAlert>()
                .ge("start_time", start).lt("start_time", end)
                .orderByAsc("id"));
        if (alerts.isEmpty()) {
            // 无无背书超温异常、也无预警：整月确无超温，真零
            m.overtempMinutes = 0L;
            return;
        }
        List<String> missingDur = new ArrayList<>();
        long minutes = 0L;
        for (TempAlert a : alerts) {
            if (a.getDurationMinutes() == null) {
                missingDur.add("预警#" + a.getId());
            } else {
                minutes += a.getDurationMinutes();
            }
        }
        if (!missingDur.isEmpty()) {
            m.pendingReasons.put(CcConstants.REG_METRIC_OVERTEMP,
                    "月内 " + alerts.size() + " 条温控预警中 " + missingDur.size()
                            + " 条缺少连续超限时长（" + String.join("、", missingDur) + "），超温时长无法完整汇总");
            return;
        }
        m.overtempMinutes = minutes;
    }

    /** 取指定类型、归属时刻（occurred 优先，空取 found）落在 [start,end) 的异常 */
    private List<ExceptionEvent> loadExceptionByType(String type, LocalDateTime start, LocalDateTime end) {
        List<ExceptionEvent> typed = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .eq("exception_type", type).orderByAsc("id"));
        List<ExceptionEvent> month = new ArrayList<>();
        for (ExceptionEvent e : typed) {
            LocalDateTime base = e.getOccurredTime() != null ? e.getOccurredTime() : e.getFoundTime();
            if (base != null && !base.isBefore(start) && base.isBefore(end)) {
                month.add(e);
            }
        }
        return month;
    }

    /** 异常关闭率：月内发生（occurred 优先，空取 found）异常的已关闭占比；归属时刻缺失/期限判定要素缺失 -> 待补 */
    private void computeExceptionCloseRate(MetricBundle m, LocalDateTime start, LocalDateTime end,
                                           int closeWindowDays) {
        // 无法归属任何统计月的异常（发生/发现时刻皆空）：无法排除其属于本月，本月关闭率不能假装完整
        long unattributable = exceptionMapper.selectCount(new QueryWrapper<ExceptionEvent>()
                .isNull("occurred_time").isNull("found_time"));
        List<ExceptionEvent> monthEvents = loadMonthExceptions(start, end);

        if (monthEvents.isEmpty()) {
            if (unattributable > 0) {
                m.pendingReasons.put(CcConstants.REG_METRIC_CLOSE_RATE,
                        "存在 " + unattributable + " 条缺少发生/发现时刻的异常，无法判定其归属月份，本月异常关闭率无法完整汇总");
                return;
            }
            // 整月无异常：无未关闭异常，关闭率记 100%（真零样本，数据齐备）
            m.exceptionTotalCount = 0;
            m.exceptionClosedCount = 0;
            m.exceptionCloseRate = new BigDecimal("100.00");
            return;
        }

        int closed = 0;
        List<String> undecidable = new ArrayList<>();
        for (ExceptionEvent e : monthEvents) {
            if (!CcConstants.EXC_RESOLVED.equals(e.getStatus())) {
                continue;
            }
            if (closeWindowDays <= 0) {
                // 不限制关闭期限：已复核关闭即计分子
                closed++;
                continue;
            }
            LocalDateTime base = e.getOccurredTime() != null ? e.getOccurredTime() : e.getFoundTime();
            if (base == null || e.getResolveTime() == null) {
                undecidable.add("异常#" + e.getId());
            } else if (!e.getResolveTime().isAfter(base.plusDays(closeWindowDays))) {
                closed++;
            }
        }
        if (unattributable > 0) {
            m.pendingReasons.put(CcConstants.REG_METRIC_CLOSE_RATE,
                    "存在 " + unattributable + " 条缺少发生/发现时刻的异常，无法判定其归属月份，本月异常关闭率无法完整汇总");
            return;
        }
        if (!undecidable.isEmpty()) {
            m.pendingReasons.put(CcConstants.REG_METRIC_CLOSE_RATE,
                    "关闭期限 " + closeWindowDays + " 天口径下，已关闭异常 "
                            + String.join("、", undecidable) + " 缺少归属/关闭时刻，无法判定是否期限内关闭");
            return;
        }
        m.exceptionTotalCount = monthEvents.size();
        m.exceptionClosedCount = closed;
        m.exceptionCloseRate = BigDecimal.valueOf(closed * 100.0 / monthEvents.size())
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 取归属时刻（occurred_time 优先，空取 found_time）落在 [start,end) 的异常 */
    private List<ExceptionEvent> loadMonthExceptions(LocalDateTime start, LocalDateTime end) {
        List<ExceptionEvent> all = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .orderByAsc("id"));
        List<ExceptionEvent> month = new ArrayList<>();
        for (ExceptionEvent e : all) {
            LocalDateTime base = e.getOccurredTime() != null ? e.getOccurredTime() : e.getFoundTime();
            if (base != null && !base.isBefore(start) && base.isBefore(end)) {
                month.add(e);
            }
        }
        return month;
    }

    /** 退货数量：月内登记退货的数量合计；任一退货缺数量 -> 指标待补 */
    private void computeReturn(MetricBundle m, LocalDateTime start, LocalDateTime end) {
        List<ReturnBatch> returns = returnBatchMapper.selectList(new QueryWrapper<ReturnBatch>()
                .ge("create_time", start).lt("create_time", end)
                .orderByAsc("id"));
        if (returns.isEmpty()) {
            m.returnQtyTotal = 0;
            return;
        }
        List<String> missingQty = new ArrayList<>();
        int qty = 0;
        for (ReturnBatch r : returns) {
            if (r.getReturnQty() == null) {
                missingQty.add(r.getReturnNo() == null ? ("退货#" + r.getId()) : r.getReturnNo());
            } else {
                qty += r.getReturnQty();
            }
        }
        if (!missingQty.isEmpty()) {
            m.pendingReasons.put(CcConstants.REG_METRIC_RETURN,
                    "月内 " + returns.size() + " 笔退货中 " + missingQty.size()
                            + " 笔缺少退货数量（" + String.join("、", missingQty) + "），退货数量无法完整汇总");
            return;
        }
        m.returnQtyTotal = qty;
    }
}
