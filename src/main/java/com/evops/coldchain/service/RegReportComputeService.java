package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.ReturnBatchMapper;
import com.evops.coldchain.mapper.TempAlertMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监管报表指标聚合（V16）。
 *
 * 按自然月 {@code [start,end)} 从台账取数，在 Java 内存按监管区域分桶聚合四指标
 * （与 V15 {@code ScoreComputeService} 同风格，不写 GROUP BY SQL）。
 *
 * 不假装成功 / 不静默填零：
 * - 超温时长：当月有发运活动但没有任何温度留痕（温控数据整体缺失）时记 NULL 并标待补，
 *   而非把超温时长算成 0；有温度留痕（哪怕全部在温区）则 0 是真实零。
 * - 异常关闭率：分母为 0（当月无异常）记 NULL（无样本，不参与）；存在无法确定归属时间的
 *   异常时标记待补；绝不用 0 冒充关闭率。
 * - 发运量/退货数量 0 是合法真实零（数据源明确、无缺失）。
 *
 * 输出始终含一个全域汇总桶（region=""），以及当月出现过的每个非空监管区域桶。
 */
@Service
public class RegReportComputeService {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private TemperatureLogMapper temperatureLogMapper;
    @Resource
    private TempAlertMapper tempAlertMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private ReturnBatchMapper returnBatchMapper;

    /** 单个监管区域（或全域 ""）的月度聚合结果 */
    public static class RegionMetrics {
        public String region;
        public Long shipVolume;
        public Long overtempMinutes;
        public Integer exceptionTotal;
        public Integer exceptionClosed;
        public BigDecimal exceptionCloseRate;
        public Long returnQty;
        public Integer returnCount;
        public List<String> missingMetrics = new ArrayList<>();
        public String pendingNote;
    }

    /**
     * 计算全域 + 各区域月度指标。
     *
     * @param caliber 生效口径（取数口径参数）
     */
    public List<RegionMetrics> compute(LocalDateTime start, LocalDateTime end,
                                       com.evops.coldchain.entity.RegReportCaliber caliber) {
        // —— 拉取窗口内明细 ——
        QueryWrapper<TransportBatch> bq = new QueryWrapper<TransportBatch>()
                .isNotNull("ship_time").ge("ship_time", start).lt("ship_time", end);
        if (!Boolean.TRUE.equals(caliber.getIncludeRevoked())) {
            bq.ne("status", CcConstants.BATCH_REVOKED);
        }
        List<TransportBatch> shippedBatches = batchMapper.selectList(bq);

        List<TemperatureLog> tempLogs = temperatureLogMapper.selectList(new QueryWrapper<TemperatureLog>()
                .ge("record_time", start).lt("record_time", end));
        List<TempAlert> alerts = tempAlertMapper.selectList(new QueryWrapper<TempAlert>()
                .ge("start_time", start).lt("start_time", end));
        List<ExceptionEvent> exceptions = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>());
        List<ReturnBatch> returns = returnBatchMapper.selectList(new QueryWrapper<ReturnBatch>()
                .ge("create_time", start).lt("create_time", end));

        // 批次 id -> 区域（含窗口外但被异常/退货关联的批次，懒加载补查）
        Map<Long, String> batchRegionCache = new HashMap<>();
        Map<Long, TransportBatch> batchCache = new HashMap<>();
        for (TransportBatch b : shippedBatches) {
            batchCache.put(b.getId(), b);
            batchRegionCache.put(b.getId(), normRegion(b.getSuperviseRegion()));
        }

        // 每个批次窗口内的温度留痕数（用于温控数据缺失判定）与 TEMP_LOG 差分
        Map<Long, List<TemperatureLog>> logsByBatch = new HashMap<>();
        for (TemperatureLog log : tempLogs) {
            logsByBatch.computeIfAbsent(log.getBatchId(), k -> new ArrayList<>()).add(log);
        }

        Map<String, Accumulator> accs = new LinkedHashMap<>();
        Accumulator all = accFor(accs, "");

        // —— 指标一：发运量 ——
        for (TransportBatch b : shippedBatches) {
            String region = normRegion(b.getSuperviseRegion());
            long delta = CcConstants.SHIP_BASIS_QTY.equals(caliber.getShipQtyBasis())
                    ? (b.getShipQty() == null ? 0L : b.getShipQty().longValue())
                    : 1L;
            all.shipVolume += delta;
            all.shippedBatchCount += 1;
            if (!region.isEmpty()) {
                accFor(accs, region).shipVolume += delta;
                accFor(accs, region).shippedBatchCount += 1;
            }
        }

        // —— 指标二：超温时长 ——
        if (CcConstants.OVERTEMP_SOURCE_LOG.equals(caliber.getOvertempSource())) {
            accumulateOvertempFromLogs(shippedBatches, logsByBatch, accs, all);
        } else {
            accumulateOvertempFromAlerts(shippedBatches, alerts, logsByBatch, batchCache,
                    batchRegionCache, accs, all);
        }

        // —— 指标三：异常关闭率 ——
        accumulateExceptions(exceptions, start, end, caliber, batchCache, batchRegionCache, accs, all);

        // —— 指标四：退货数量 ——
        for (ReturnBatch r : returns) {
            String region = regionOfBatch(r.getSourceBatchId(), batchCache, batchRegionCache);
            long qty = r.getReturnQty() == null ? 0L : r.getReturnQty().longValue();
            all.returnQty += qty;
            all.returnCount += 1;
            if (!region.isEmpty()) {
                Accumulator a = accFor(accs, region);
                a.returnQty += qty;
                a.returnCount += 1;
            }
        }

        // —— 汇总为结果 ——
        List<RegionMetrics> result = new ArrayList<>();
        for (Map.Entry<String, Accumulator> e : accs.entrySet()) {
            result.add(e.getValue().toMetrics(e.getKey(), caliber));
        }
        return result;
    }

    /** TEMP_ALERT 口径：达容忍连续超温段时长求和（一条预警一段） */
    private void accumulateOvertempFromAlerts(List<TransportBatch> shippedBatches, List<TempAlert> alerts,
                                              Map<Long, List<TemperatureLog>> logsByBatch,
                                              Map<Long, TransportBatch> batchCache,
                                              Map<Long, String> batchRegionCache,
                                              Map<String, Accumulator> accs, Accumulator all) {
        // 仅统计窗口内发运批次相关的预警（监管口径与当月发运挂钩），按预警批次区域归集
        for (TempAlert a : alerts) {
            String region = regionOfBatch(a.getBatchId(), batchCache, batchRegionCache);
            long minutes = a.getDurationMinutes() == null ? 0L : a.getDurationMinutes();
            all.overtempMinutes += minutes;
            if (!region.isEmpty()) {
                accFor(accs, region).overtempMinutes += minutes;
            }
        }
        // 温控数据缺失判定：有发运但该桶批次窗口内无任何温度留痕 -> 待补（不填零）
        markOvertempMissingIfNoTempData(shippedBatches, logsByBatch, accs, all);
    }

    /** TEMP_LOG 口径：同批次连续越温点相邻时间差累加（正常点打断），排除补传豁免点 */
    private void accumulateOvertempFromLogs(List<TransportBatch> shippedBatches,
                                            Map<Long, List<TemperatureLog>> logsByBatch,
                                            Map<String, Accumulator> accs, Accumulator all) {
        for (TransportBatch b : shippedBatches) {
            List<TemperatureLog> logs = logsByBatch.getOrDefault(b.getId(), new ArrayList<>());
            List<TemperatureLog> bad = new ArrayList<>();
            for (TemperatureLog l : logs) {
                if (Boolean.FALSE.equals(l.getInRange()) && !Boolean.TRUE.equals(l.getAlertExempt())) {
                    bad.add(l);
                }
            }
            bad.sort(Comparator.comparing(TemperatureLog::getRecordTime));
            long minutes = 0L;
            for (int i = 1; i < bad.size(); i++) {
                long gap = Duration.between(bad.get(i - 1).getRecordTime(), bad.get(i).getRecordTime()).toMinutes();
                if (gap > 0) {
                    minutes += gap;
                }
            }
            String region = normRegion(b.getSuperviseRegion());
            all.overtempMinutes += minutes;
            if (!region.isEmpty()) {
                accFor(accs, region).overtempMinutes += minutes;
            }
        }
        markOvertempMissingIfNoTempData(shippedBatches, logsByBatch, accs, all);
    }

    /** 有发运活动但完全没有温度留痕的桶，超温指标置缺失（NULL + 待补），不静默填零 */
    private void markOvertempMissingIfNoTempData(List<TransportBatch> shippedBatches,
                                                 Map<Long, List<TemperatureLog>> logsByBatch,
                                                 Map<String, Accumulator> accs, Accumulator all) {
        boolean allHasTemp = false;
        Map<String, Boolean> regionHasTemp = new HashMap<>();
        for (TransportBatch b : shippedBatches) {
            boolean has = !logsByBatch.getOrDefault(b.getId(), new ArrayList<>()).isEmpty();
            allHasTemp |= has;
            String region = normRegion(b.getSuperviseRegion());
            if (!region.isEmpty()) {
                regionHasTemp.merge(region, has, (x, y) -> x || y);
            }
        }
        if (all.shippedBatchCount > 0 && !allHasTemp) {
            all.overtempMissing = true;
        }
        for (Accumulator a : accs.values()) {
            if (a == all) {
                continue;
            }
            if (a.shippedBatchCount > 0 && !regionHasTemp.getOrDefault(a.regionKey, false)) {
                a.overtempMissing = true;
            }
        }
    }

    /**
     * 异常关闭率，两种自洽口径：
     * - FOUND_MONTH：分母=当月发现/发生的异常，分子=其中截至取数时已 RESOLVED 的（反映当月异常处置结果）。
     * - RESOLVE_MONTH：分母=当月应处置异常（月初仍未关闭的存量 + 当月新增，即 found<月末 且 未在月初前关闭），
     *   分子=其中在当月内完成关闭的（反映当月处置吞吐）。
     * 无法确定归属时间（found/occurred 均空）的异常计入 timeMissing -> 指标待补。
     */
    private void accumulateExceptions(List<ExceptionEvent> exceptions, LocalDateTime start, LocalDateTime end,
                                      com.evops.coldchain.entity.RegReportCaliber caliber,
                                      Map<Long, TransportBatch> batchCache,
                                      Map<Long, String> batchRegionCache,
                                      Map<String, Accumulator> accs, Accumulator all) {
        boolean resolveBasis = CcConstants.EXC_CLOSE_BASIS_RESOLVE.equals(caliber.getExceptionCloseBasis());
        for (ExceptionEvent e : exceptions) {
            LocalDateTime belong = e.getFoundTime() != null ? e.getFoundTime() : e.getOccurredTime();
            String region = regionOfBatch(e.getBatchId(), batchCache, batchRegionCache);
            boolean resolved = CcConstants.EXC_RESOLVED.equals(e.getStatus());
            boolean resolvedInMonth = e.getResolveTime() != null
                    && !e.getResolveTime().isBefore(start) && e.getResolveTime().isBefore(end);
            boolean resolvedBeforeMonth = e.getResolveTime() != null && e.getResolveTime().isBefore(start);

            boolean inDenominator;
            boolean inNumerator;
            boolean timeMissing = false;
            if (belong == null) {
                // 无任何归属时间，无法归集：两种口径都标待补
                inDenominator = false;
                inNumerator = false;
                timeMissing = true;
            } else if (resolveBasis) {
                // 当月应处置 = 归属早于月末，且未在月初之前关闭
                inDenominator = belong.isBefore(end) && !resolvedBeforeMonth;
                inNumerator = inDenominator && resolvedInMonth;
            } else {
                // 当月发现
                inDenominator = !belong.isBefore(start) && belong.isBefore(end);
                inNumerator = inDenominator && resolved;
            }

            if (timeMissing) {
                all.exceptionTimeMissing++;
                if (!region.isEmpty()) {
                    accFor(accs, region).exceptionTimeMissing++;
                }
            }
            if (inDenominator) {
                all.exceptionTotal++;
                if (!region.isEmpty()) {
                    accFor(accs, region).exceptionTotal++;
                }
            }
            if (inNumerator) {
                all.exceptionClosed++;
                if (!region.isEmpty()) {
                    accFor(accs, region).exceptionClosed++;
                }
            }
        }
    }

    private Accumulator accFor(Map<String, Accumulator> accs, String region) {
        return accs.computeIfAbsent(region, k -> {
            Accumulator a = new Accumulator();
            a.regionKey = k;
            return a;
        });
    }

    private String regionOfBatch(Long batchId, Map<Long, TransportBatch> batchCache,
                                 Map<Long, String> batchRegionCache) {
        if (batchId == null) {
            return "";
        }
        String cached = batchRegionCache.get(batchId);
        if (cached != null) {
            return cached;
        }
        TransportBatch b = batchMapper.selectById(batchId);
        String region = b == null ? "" : normRegion(b.getSuperviseRegion());
        if (b != null) {
            batchCache.put(b.getId(), b);
        }
        batchRegionCache.put(batchId, region);
        return region;
    }

    private static String normRegion(String region) {
        return region == null ? "" : region.trim();
    }

    /** 累加器：聚合后转成 RegionMetrics */
    private static class Accumulator {
        String regionKey;
        long shipVolume;
        int shippedBatchCount;
        long overtempMinutes;
        boolean overtempMissing;
        int exceptionTotal;
        int exceptionClosed;
        int exceptionTimeMissing;
        long returnQty;
        int returnCount;

        RegionMetrics toMetrics(String region, com.evops.coldchain.entity.RegReportCaliber caliber) {
            RegionMetrics m = new RegionMetrics();
            m.region = region;
            m.shipVolume = shipVolume;
            m.returnQty = returnQty;
            m.returnCount = returnCount;
            m.exceptionTotal = exceptionTotal;
            m.exceptionClosed = exceptionClosed;
            m.exceptionCloseRate = exceptionTotal > 0
                    ? BigDecimal.valueOf(exceptionClosed * 100.0 / exceptionTotal)
                        .setScale(2, RoundingMode.HALF_UP)
                    : null;

            List<String> missing = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            if (overtempMissing) {
                m.overtempMinutes = null;
                missing.add(CcConstants.METRIC_OVERTEMP);
                notes.add("当月有发运批次但无任何温度留痕，超温时长无法核算，标记待补（未填零）");
            } else {
                m.overtempMinutes = overtempMinutes;
            }
            if (exceptionTimeMissing > 0) {
                missing.add(CcConstants.METRIC_EXC_CLOSE);
                notes.add(String.format("有 %d 条异常缺少发现/发生时间，无法归入统计月，关闭率标记待补",
                        exceptionTimeMissing));
                m.exceptionCloseRate = null;
            } else if (exceptionTotal == 0) {
                notes.add("当月无异常样本，异常关闭率记 NULL（不填零）");
            }
            m.missingMetrics = missing;
            m.pendingNote = notes.isEmpty() ? null : String.join("；", notes);
            return m;
        }
    }
}
