package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.Carrier;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.LockedPrice;
import com.evops.coldchain.entity.ScoreEvidence;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.entity.SignReceipt;
import com.evops.coldchain.entity.SignReceiptPhoto;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.CarrierMapper;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.LockedPriceMapper;
import com.evops.coldchain.mapper.SignReceiptMapper;
import com.evops.coldchain.mapper.SignReceiptPhotoMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 承运商评分指标计算（V15）。
 *
 * 四项指标分别取证（周期归属：批次按签收时间、异常按确认时间）：
 * - 准时率：签收时刻 ≤ 承诺到达时刻为 PASS；无承诺时间的批次 UNEVALUABLE（不进分母）；
 * - 温控合格率：批次存在温度超限异常为 FAIL，否则 PASS；
 * - 签收完整度：存在要素齐全（签收人/签名/照片/定位/双时间）的电子回执为 PASS，否则 FAIL；
 * - 异常响应：确认时刻 ≤ 首次发现 + 响应时限快照为 PASS，超时 FAIL；缺时钟要素 UNEVALUABLE。
 *
 * 不得假装成功：无法取证一律单独计数不进分母；指标无样本比率记 NULL；
 * 四项全无样本总分记 NULL（无法评分）。
 */
@Service
public class ScoreComputeService {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private SignReceiptMapper receiptMapper;
    @Resource
    private SignReceiptPhotoMapper receiptPhotoMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private LockedPriceMapper lockedPriceMapper;
    @Resource
    private CarrierMapper carrierMapper;

    /** 一个承运商一个周期的计算结果（快照 + 取证明细，未落库） */
    public static class CarrierScore {
        public ScoreSnapshot snapshot;
        public final List<ScoreEvidence> evidences = new ArrayList<>();
    }

    private static class Counter {
        int pass;
        int fail;
        int unevaluable;

        BigDecimal rate() {
            int base = pass + fail;
            if (base == 0) {
                return null;
            }
            return BigDecimal.valueOf(pass * 100.0 / base).setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * 计算一个周期内全部承运商的评分（按周期绑定规则版本的权重）。
     * 只计算周期内有台账活动（签收批次或确认异常）的承运商。
     */
    public List<CarrierScore> compute(Long periodId, String periodNo,
                                      LocalDateTime start, LocalDateTime end,
                                      Integer ruleVersionNo, String ruleSnapshot,
                                      int wOnTime, int wTemp, int wSign, int wResp,
                                      LocalDateTime generateTime) {
        // 1) 周期内签收的批次 -> 承运商归属
        List<TransportBatch> signedBatches = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .isNotNull("sign_time")
                .ge("sign_time", start)
                .lt("sign_time", end)
                .orderByAsc("id"));
        Map<Long, List<TransportBatch>> batchesByCarrier = new LinkedHashMap<>();
        for (TransportBatch batch : signedBatches) {
            Long carrierId = attributeCarrier(batch);
            if (carrierId == null) {
                continue; // 无法归属承运商的批次不计入任何评分
            }
            batchesByCarrier.computeIfAbsent(carrierId, k -> new ArrayList<>()).add(batch);
        }

        // 2) 周期内确认的异常 -> 按批次归属承运商
        List<ExceptionEvent> confirmed = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                .isNotNull("confirm_time")
                .ge("confirm_time", start)
                .lt("confirm_time", end)
                .orderByAsc("id"));
        Map<Long, List<ExceptionEvent>> exceptionsByCarrier = new LinkedHashMap<>();
        Map<Long, TransportBatch> batchCache = new HashMap<>();
        for (ExceptionEvent event : confirmed) {
            TransportBatch batch = batchCache.computeIfAbsent(event.getBatchId(),
                    id -> batchMapper.selectById(id));
            if (batch == null) {
                continue;
            }
            Long carrierId = attributeCarrier(batch);
            if (carrierId == null) {
                continue;
            }
            exceptionsByCarrier.computeIfAbsent(carrierId, k -> new ArrayList<>()).add(event);
        }

        // 3) 温度异常按批次预取（温控合格率取证）
        Map<Long, List<ExceptionEvent>> tempExcByBatch = new HashMap<>();
        for (Long carrierId : batchesByCarrier.keySet()) {
            for (TransportBatch batch : batchesByCarrier.get(carrierId)) {
                List<ExceptionEvent> temps = exceptionMapper.selectList(new QueryWrapper<ExceptionEvent>()
                        .eq("batch_id", batch.getId())
                        .eq("exception_type", CcConstants.EXC_TEMP)
                        .orderByAsc("id"));
                tempExcByBatch.put(batch.getId(), temps);
            }
        }

        // 4) 逐承运商计算
        List<CarrierScore> results = new ArrayList<>();
        List<Long> carrierIds = batchesByCarrier.keySet().stream()
                .sorted().collect(Collectors.toList());
        for (Long carrierId : carrierIds) {
            Carrier carrier = carrierMapper.selectById(carrierId);
            if (carrier == null) {
                continue;
            }
            CarrierScore cs = new CarrierScore();
            ScoreSnapshot snap = new ScoreSnapshot();
            snap.setPeriodId(periodId);
            snap.setPeriodNo(periodNo);
            snap.setCarrierId(carrierId);
            snap.setCarrierNoSnap(carrier.getCarrierNo());
            snap.setCarrierNameSnap(carrier.getCarrierName());
            snap.setRuleVersionNo(ruleVersionNo);
            snap.setRuleSnapshot(ruleSnapshot);
            snap.setGenerateTime(generateTime);
            cs.snapshot = snap;

            List<TransportBatch> batches = batchesByCarrier.getOrDefault(carrierId, new ArrayList<>());
            List<ExceptionEvent> exceptions = exceptionsByCarrier.getOrDefault(carrierId, new ArrayList<>());

            Counter onTime = new Counter();
            Counter temp = new Counter();
            Counter sign = new Counter();
            Counter resp = new Counter();

            for (TransportBatch batch : batches) {
                evalOnTime(cs, onTime, batch);
                evalTempControl(cs, temp, batch, tempExcByBatch.getOrDefault(batch.getId(), new ArrayList<>()));
                evalSignComplete(cs, sign, batch);
            }
            for (ExceptionEvent event : exceptions) {
                evalExceptionResponse(cs, resp, event);
            }

            snap.setOnTimePass(onTime.pass);
            snap.setOnTimeFail(onTime.fail);
            snap.setOnTimeUnevaluable(onTime.unevaluable);
            snap.setOnTimeRate(onTime.rate());
            snap.setTempControlPass(temp.pass);
            snap.setTempControlFail(temp.fail);
            snap.setTempControlUnevaluable(temp.unevaluable);
            snap.setTempControlRate(temp.rate());
            snap.setSignCompletePass(sign.pass);
            snap.setSignCompleteFail(sign.fail);
            snap.setSignCompleteUnevaluable(sign.unevaluable);
            snap.setSignCompleteRate(sign.rate());
            snap.setExceptionRespPass(resp.pass);
            snap.setExceptionRespFail(resp.fail);
            snap.setExceptionRespUnevaluable(resp.unevaluable);
            snap.setExceptionRespRate(resp.rate());

            // 总分：有样本指标按权重重归一化；四项全无样本 -> NULL（无法评分，不假装成功）
            BigDecimal total = null;
            int scored = 0;
            BigDecimal weightSum = BigDecimal.ZERO;
            BigDecimal scoreSum = BigDecimal.ZERO;
            if (snap.getOnTimeRate() != null) {
                scored++;
                weightSum = weightSum.add(BigDecimal.valueOf(wOnTime));
                scoreSum = scoreSum.add(snap.getOnTimeRate().multiply(BigDecimal.valueOf(wOnTime)));
            }
            if (snap.getTempControlRate() != null) {
                scored++;
                weightSum = weightSum.add(BigDecimal.valueOf(wTemp));
                scoreSum = scoreSum.add(snap.getTempControlRate().multiply(BigDecimal.valueOf(wTemp)));
            }
            if (snap.getSignCompleteRate() != null) {
                scored++;
                weightSum = weightSum.add(BigDecimal.valueOf(wSign));
                scoreSum = scoreSum.add(snap.getSignCompleteRate().multiply(BigDecimal.valueOf(wSign)));
            }
            if (snap.getExceptionRespRate() != null) {
                scored++;
                weightSum = weightSum.add(BigDecimal.valueOf(wResp));
                scoreSum = scoreSum.add(snap.getExceptionRespRate().multiply(BigDecimal.valueOf(wResp)));
            }
            if (scored > 0 && weightSum.signum() > 0) {
                total = scoreSum.divide(weightSum, 2, RoundingMode.HALF_UP);
            }
            snap.setScoredMetricCount(scored);
            snap.setTotalScore(total);

            results.add(cs);
        }
        return results;
    }

    /** 承运商归属：车辆 carrier_id 优先，锁价单 carrier_id 兜底；均无则 null（不计入任何承运商） */
    private Long attributeCarrier(TransportBatch batch) {
        if (batch.getVehicleId() != null) {
            Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
            if (vehicle != null && vehicle.getCarrierId() != null) {
                return vehicle.getCarrierId();
            }
        }
        if (batch.getLockedPriceId() != null) {
            LockedPrice lp = lockedPriceMapper.selectById(batch.getLockedPriceId());
            if (lp != null && lp.getCarrierId() != null) {
                return lp.getCarrierId();
            }
        }
        return null;
    }

    /** 准时率：签收时刻 ≤ 承诺到达时刻；无承诺时间无法取证（不进分母） */
    private void evalOnTime(CarrierScore cs, Counter counter, TransportBatch batch) {
        String result;
        String basis;
        if (batch.getCommittedArrivalTime() == null) {
            counter.unevaluable++;
            result = CcConstants.EVIDENCE_UNEVALUABLE;
            basis = String.format("批次 %s 无承诺到达时间（未走送达时窗解析），准时率无法取证，不计入分母",
                    batch.getBatchNo());
        } else if (!batch.getSignTime().isAfter(batch.getCommittedArrivalTime())) {
            counter.pass++;
            result = CcConstants.EVIDENCE_PASS;
            basis = String.format("批次 %s 签收 %s ≤ 承诺到达 %s，准时",
                    batch.getBatchNo(), batch.getSignTime(), batch.getCommittedArrivalTime());
        } else {
            counter.fail++;
            result = CcConstants.EVIDENCE_FAIL;
            basis = String.format("批次 %s 签收 %s 晚于承诺到达 %s，晚点",
                    batch.getBatchNo(), batch.getSignTime(), batch.getCommittedArrivalTime());
        }
        cs.evidences.add(evidence(cs.snapshot, CcConstants.METRIC_ON_TIME, batch.getId(), null, null, result, basis));
    }

    /** 温控合格率：批次存在温度超限异常为 FAIL */
    private void evalTempControl(CarrierScore cs, Counter counter, TransportBatch batch, List<ExceptionEvent> temps) {
        String result;
        String basis;
        if (temps.isEmpty()) {
            counter.pass++;
            result = CcConstants.EVIDENCE_PASS;
            basis = String.format("批次 %s 全程无温度超限异常，温控合格", batch.getBatchNo());
        } else {
            counter.fail++;
            result = CcConstants.EVIDENCE_FAIL;
            String ids = temps.stream().map(e -> "#" + e.getId()).collect(Collectors.joining(","));
            basis = String.format("批次 %s 发生 %d 起温度超限异常（%s），温控不合格",
                    batch.getBatchNo(), temps.size(), ids);
        }
        Long excId = temps.isEmpty() ? null : temps.get(0).getId();
        cs.evidences.add(evidence(cs.snapshot, CcConstants.METRIC_TEMP_CONTROL, batch.getId(), excId, null, result, basis));
    }

    /** 签收完整度：存在要素齐全（签收人/签名/照片/定位/双时间）的 COMPLETE/EFFECTIVE 电子回执 */
    private void evalSignComplete(CarrierScore cs, Counter counter, TransportBatch batch) {
        List<SignReceipt> receipts = receiptMapper.selectList(new QueryWrapper<SignReceipt>()
                .eq("batch_id", batch.getId())
                .in("status", CcConstants.RECEIPT_COMPLETE, CcConstants.RECEIPT_EFFECTIVE)
                .orderByAsc("id"));
        SignReceipt complete = null;
        List<String> missing = new ArrayList<>();
        for (SignReceipt r : receipts) {
            List<String> lack = missingElements(r);
            if (lack.isEmpty()) {
                complete = r;
                break;
            }
            missing = lack;
        }
        String result;
        String basis;
        Long receiptId = null;
        if (complete != null) {
            counter.pass++;
            result = CcConstants.EVIDENCE_PASS;
            receiptId = complete.getId();
            basis = String.format("批次 %s 电子回执 %s 要素齐全（签收人/签名/照片/定位/双时间），签收完整",
                    batch.getBatchNo(), complete.getReceiptNo());
        } else if (receipts.isEmpty()) {
            counter.fail++;
            result = CcConstants.EVIDENCE_FAIL;
            basis = String.format("批次 %s 已签收但无电子签收回执，签收要素不完整", batch.getBatchNo());
        } else {
            counter.fail++;
            result = CcConstants.EVIDENCE_FAIL;
            receiptId = receipts.get(0).getId();
            basis = String.format("批次 %s 电子回执要素缺失：%s", batch.getBatchNo(), String.join("、", missing));
        }
        cs.evidences.add(evidence(cs.snapshot, CcConstants.METRIC_SIGN_COMPLETE, batch.getId(), null, receiptId, result, basis));
    }

    private List<String> missingElements(SignReceipt r) {
        List<String> lack = new ArrayList<>();
        if (r.getSignBy() == null || r.getSignBy().trim().isEmpty()) {
            lack.add("签收人");
        }
        if ((r.getSignatureName() == null || r.getSignatureName().trim().isEmpty())
                && (r.getSignatureUrl() == null || r.getSignatureUrl().trim().isEmpty())) {
            lack.add("签名");
        }
        Long photos = receiptPhotoMapper.selectCount(new QueryWrapper<SignReceiptPhoto>()
                .eq("receipt_id", r.getId()));
        if (photos == null || photos == 0) {
            lack.add("照片");
        }
        if (r.getLocationLng() == null && r.getLocationLat() == null
                && (r.getLocationAddress() == null || r.getLocationAddress().trim().isEmpty())) {
            lack.add("定位");
        }
        if (r.getDeviceTime() == null || r.getServerTime() == null) {
            lack.add("设备/服务器时间");
        }
        return lack;
    }

    /** 异常响应：确认时刻 ≤ 首次发现 + 响应时限快照；缺时钟要素无法取证 */
    private void evalExceptionResponse(CarrierScore cs, Counter counter, ExceptionEvent event) {
        String result;
        String basis;
        if (event.getFoundTime() == null || event.getConfirmTime() == null || event.getResponseMinutesSnap() == null) {
            counter.unevaluable++;
            result = CcConstants.EVIDENCE_UNEVALUABLE;
            basis = String.format("异常#%d（%s）缺少处置时钟要素（首次发现/确认时刻/响应时限快照），异常响应无法取证，不计入分母",
                    event.getId(), event.getExceptionType());
        } else {
            LocalDateTime due = event.getFoundTime().plusMinutes(event.getResponseMinutesSnap());
            if (!event.getConfirmTime().isAfter(due)) {
                counter.pass++;
                result = CcConstants.EVIDENCE_PASS;
                basis = String.format("异常#%d（%s）首次发现 %s，确认 %s ≤ 响应截止 %s（时限%d分钟），响应及时",
                        event.getId(), event.getExceptionType(), event.getFoundTime(),
                        event.getConfirmTime(), due, event.getResponseMinutesSnap());
            } else {
                counter.fail++;
                result = CcConstants.EVIDENCE_FAIL;
                basis = String.format("异常#%d（%s）首次发现 %s，确认 %s 晚于响应截止 %s（时限%d分钟），响应超时",
                        event.getId(), event.getExceptionType(), event.getFoundTime(),
                        event.getConfirmTime(), due, event.getResponseMinutesSnap());
            }
        }
        cs.evidences.add(evidence(cs.snapshot, CcConstants.METRIC_EXCEPTION_RESPONSE,
                event.getBatchId(), event.getId(), null, result, basis));
    }

    private ScoreEvidence evidence(ScoreSnapshot snap, String metric, Long batchId, Long exceptionId,
                                   Long receiptId, String result, String basis) {
        ScoreEvidence e = new ScoreEvidence();
        e.setSnapshotId(snap.getId());
        e.setPeriodId(snap.getPeriodId());
        e.setCarrierId(snap.getCarrierId());
        e.setMetricType(metric);
        e.setBatchId(batchId);
        e.setExceptionId(exceptionId);
        e.setReceiptId(receiptId);
        e.setResult(result);
        e.setBasis(basis);
        return e;
    }
}
