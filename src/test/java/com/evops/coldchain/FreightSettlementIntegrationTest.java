package com.evops.coldchain;

import com.evops.coldchain.dto.BaseRateItem;
import com.evops.coldchain.dto.ContractDraftRequest;
import com.evops.coldchain.dto.EvidenceAppendRequest;
import com.evops.coldchain.dto.ExceptionMarkRequest;
import com.evops.coldchain.dto.SettlementAuditRequest;
import com.evops.coldchain.dto.SettlementDisputeRequest;
import com.evops.coldchain.dto.SettlementPreviewRequest;
import com.evops.coldchain.dto.SettlementReviewRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.TempReportRequest;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.FreightContract;
import com.evops.coldchain.entity.FreightReplay;
import com.evops.coldchain.entity.FreightReplayItem;
import com.evops.coldchain.entity.FreightSettlement;
import com.evops.coldchain.entity.FreightSettleItem;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.dto.FreightReplayRequest;
import com.evops.coldchain.service.ExceptionLifecycleService;
import com.evops.coldchain.service.FreightContractService;
import com.evops.coldchain.service.FreightPricingEngine;
import com.evops.coldchain.service.FreightReplayService;
import com.evops.coldchain.service.FreightSettlementService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.support.TestSubjects;
import com.evops.common.BusinessException;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输 V14 验收：运输费用预结算。
 *
 * 用例严格按顺序串成一条业务线：
 * 1) 用冷链药品运输旧数据（含延误 L3 + 超温 L1 已复核关闭的已签收批次）按 V1 基础合同
 *    预览（每项费用依据）、生成快照、财务审核冻结；
 * 2) 岗位字段范围：运营视图掩码单价/计价依据/规则快照，财务视图可见；
 * 3) 财务发布新版本 V2「运输费用按异常扣罚」（HTTP 权限：运营 403、财务 200）；
 * 4) 旧数据回放：已审核批次差异恒 0（冻结不重算），未结算批次按新版本扣罚，差异逐项可解释；
 * 5) 争议项进入待复核 -> 财务复核“不计入”重算应付 -> 审核冻结，之后新版本/重放均不能重算。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FreightSettlementIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 2, 8, 0);
    private static final String BOX = "REFRIGERATED_BOX";
    private static final BigDecimal MILEAGE = new BigDecimal("300.00");
    /** V1 种子：冷藏箱 200~500km 档 5.80 元/km，300km → 1740.00 元（最低收费 1000 不取） */
    private static final BigDecimal BASE_300 = new BigDecimal("1740.00");

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;

    @Autowired private TransportBatchService batchService;
    @Autowired private ExceptionLifecycleService lifecycle;
    @Autowired private FreightContractService contractService;
    @Autowired private FreightSettlementService settlementService;
    @Autowired private FreightReplayService replayService;
    @Autowired private TestSubjects testSubjects;

    // 跨用例共享的业务线状态
    private static Long auditedBatchId;
    private static Long auditedSettlementId;
    private static Long openBatchId;
    private static Long delayOnlyBatchId;
    private static Long contractV1Id;
    private static Long contractV2Id;
    private static Long v2SettlementId;
    private static Long v2PenaltyItemId;

    // ========== 1) V1 旧数据：预览 → 生成 → 审核冻结 ==========

    @Test
    @Order(1)
    void oldData_v1PreviewGenerateAndAuditFreeze() {
        contractV1Id = contractService.effectiveContract().getId();
        assertEquals(1, contractService.effectiveContract().getVersionNo());

        // 旧批次 A：延误 L3 + 超温 L1，均复核关闭后签收（V1 合同无扣罚规则，只有基础运费）
        auditedBatchId = signedBatchWith("V14旧批A", true, true);
        SettlementPreviewRequest req = previewReq(auditedBatchId, contractV1Id);
        Map<String, Object> preview = settlementService.preview(req);
        assertEquals(0, new BigDecimal(preview.get("baseAmount").toString()).compareTo(BASE_300));
        assertEquals(0, new BigDecimal(preview.get("penaltyAmount").toString()).compareTo(BigDecimal.ZERO));
        @SuppressWarnings("unchecked")
        List<FreightPricingEngine.CalcItem> items =
                (List<FreightPricingEngine.CalcItem>) preview.get("items");
        assertEquals(1, items.size(), "V1 无扣罚约定，预览仅基础运费一项");
        FreightPricingEngine.CalcItem baseItem = items.get(0);
        assertTrue(baseItem.basisBusiness.contains("冷藏箱"));
        assertTrue(baseItem.basisPricing.contains("5.80"));
        assertTrue(baseItem.basisPricing.contains("1740.00"));

        FreightSettlement settlement = settlementService.generate(req);
        auditedSettlementId = settlement.getId();
        assertEquals(CcConstants.FST_PENDING, settlement.getStatus());
        assertEquals(0, settlement.getBaseAmount().compareTo(BASE_300));
        assertNotNull(settlement.getRuleSnapshot());
        assertTrue(settlement.getRuleSnapshot().contains("\"versionNo\":1"));

        // 财务审核冻结
        FreightSettlement audited = testSubjects.apply(10L, "finance",
                () -> settlementService.audit(auditReq(auditedSettlementId, "V14旧账核对无误")));
        assertEquals(CcConstants.FST_AUDITED, audited.getStatus());
        assertNotNull(audited.getAuditTime());

        // 已审核不能重复审核
        assertThrows(BusinessException.class,
                () -> settlementService.audit(auditReq(auditedSettlementId, "再次审核")));
    }

    // ========== 2) 岗位字段范围 ==========

    @Test
    @Order(2)
    void fieldsScopedByRole_operationsMasked_financeFull() {
        // 运营（调度 dispatcher=2）：单价/计价依据/规则快照/审核账号全部收窄
        testSubjects.runAs(2L, "dispatcher", () -> {
            Map<String, Object> view = settlementService.detail(auditedSettlementId);
            assertEquals("OPERATIONS", view.get("viewerScope"));
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) view.get("settlement");
            assertNull(s.get("ruleSnapshot"), "运营不可见规则快照");
            assertNull(s.get("auditByName"), "运营不可见审核人");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) view.get("items");
            assertNull(items.get(0).get("unitPrice"), "运营不可见单价");
            assertNull(items.get(0).get("basisPricing"), "运营不可见计价依据");
            assertNotNull(items.get(0).get("basisBusiness"), "运营可见业务依据");
            assertEquals(BASE_300, items.get(0).get("amount"), "运营仍可见金额用于对账");
        });

        // 财务（finance=10）：全部字段可见
        testSubjects.runAs(10L, "finance", () -> {
            Map<String, Object> view = settlementService.detail(auditedSettlementId);
            assertEquals("FINANCE", view.get("viewerScope"));
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) view.get("settlement");
            assertNotNull(s.get("ruleSnapshot"));
            assertNotNull(s.get("auditByName"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) view.get("items");
            assertEquals(0, new BigDecimal(items.get(0).get("unitPrice").toString()).compareTo(new BigDecimal("5.80")));
            assertNotNull(items.get(0).get("basisPricing"));
        });
    }

    // ========== 3) 发布 V2（HTTP 岗位权限） ==========

    @Test
    @Order(3)
    void publishV2_contractManageIsFinanceOnly() {
        // 运营起草合同新版本 → 403
        ResponseEntity<Map> denied = http.withBasicAuth("dispatcher", "dispatcher123")
                .postForEntity(url("/freight/contracts/draft"), draftBody(), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        // 承运岗连结算台账都不可见 → 403
        ResponseEntity<Map> noView = http.withBasicAuth("carrier", "carrier123")
                .getForEntity(url("/freight/settlements"), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, noView.getStatusCode());

        // 财务起草 V2 并发布
        ResponseEntity<Map> draftResp = http.withBasicAuth("finance", "finance123")
                .postForEntity(url("/freight/contracts/draft"), draftBody(), Map.class);
        assertEquals(HttpStatus.OK, draftResp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> drafted = (Map<String, Object>) draftResp.getBody().get("data");
        contractV2Id = ((Number) drafted.get("id")).longValue();
        assertEquals(2, ((Number) drafted.get("versionNo")).intValue());
        assertEquals(CcConstants.FC_DRAFT, drafted.get("status"));

        ResponseEntity<Map> pubResp = http.withBasicAuth("finance", "finance123")
                .postForEntity(url("/freight/contracts/" + contractV2Id + "/publish"), null, Map.class);
        assertEquals(HttpStatus.OK, pubResp.getStatusCode());
        FreightContract v2 = contractService.requireContract(contractV2Id);
        assertEquals(CcConstants.EFFECTIVE, v2.getStatus());
        assertEquals("运输费用按异常扣罚", v2.getVersionName());
        // V1 被热替换为 SUPERSEDED，不能再用来计算新费用（预览在落库前即拦截）
        assertEquals(CcConstants.SUPERSEDED, contractService.requireContract(contractV1Id).getStatus());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> settlementService.preview(previewReq(auditedBatchId, contractV1Id)));
        assertTrue(ex.getMessage().contains("生效"));
    }

    // ========== 4) 旧数据回放：冻结不重算 + 差异逐项解释 ==========

    @Test
    @Order(4)
    void replay_oldData_auditedFrozenZeroDiff_othersExplainPenalties() {
        // 第二条旧批次：同样含延误+超温但未做结算（按新 V2 规则将产生扣罚）
        openBatchId = signedBatchWith("V14旧批B", true, true);
        FreightReplayRequest req = new FreightReplayRequest();
        req.setTargetContractId(contractV2Id);
        req.setBatchIds(Arrays.asList(auditedBatchId, openBatchId));
        req.setDefaultBoxType(BOX);
        req.setDefaultMileageKm(MILEAGE);

        Map<String, Object> result = replayService.replay(req);
        FreightReplay report = (FreightReplay) result.get("report");
        assertEquals(2, report.getBatchCount());
        assertEquals(1, report.getFrozenCount(), "已审核批次单独计入冻结数");
        assertEquals(1, report.getChangedCount());

        @SuppressWarnings("unchecked")
        List<FreightReplayItem> rows = (List<FreightReplayItem>) result.get("items");
        FreightReplayItem frozenRow = rows.stream()
                .filter(r -> r.getBatchId().equals(auditedBatchId)).findFirst().orElseThrow(AssertionError::new);
        assertTrue(frozenRow.getFrozen());
        assertEquals(0, frozenRow.getDiffAmount().compareTo(BigDecimal.ZERO), "已审核结算不被新规则重算");
        assertEquals(0, frozenRow.getTargetPayable().compareTo(BASE_300));
        assertTrue(frozenRow.getDiffDetail().contains("已财务审核冻结"));

        FreightReplayItem openRow = rows.stream()
                .filter(r -> r.getBatchId().equals(openBatchId)).findFirst().orElseThrow(AssertionError::new);
        assertFalse(openRow.getFrozen());
        // V2：延误 L3 定额 200；超温 L1 按基础运费 30%（1740×30%=522，未触封顶 5000）；应付 1740-722=1018
        assertEquals(0, openRow.getBaselinePayable().compareTo(BASE_300));
        assertEquals(0, openRow.getTargetPenaltyAmount().compareTo(new BigDecimal("722.00")));
        assertEquals(0, openRow.getTargetPayable().compareTo(new BigDecimal("1018.00")));
        assertEquals(0, openRow.getDiffAmount().compareTo(new BigDecimal("-722.00")));
        // 差异必须可解释：逐异常单说明新增扣罚，且有汇总行
        assertTrue(openRow.getDiffDetail().contains("运输延误"));
        assertTrue(openRow.getDiffDetail().contains("温度超限"));
        assertTrue(openRow.getDiffDetail().contains("新增扣罚"));
        assertTrue(openRow.getDiffDetail().contains("200"));
        assertTrue(openRow.getDiffDetail().contains("522"));
        assertTrue(openRow.getDiffDetail().contains("-722.00"));
    }

    // ========== 5) 争议 → 待复核 → 财务复核不计入 → 审核冻结后不再重算 ==========

    @Test
    @Order(5)
    void disputePending_financeReviewExcludedRecalculates_thenAuditFreezes() {
        // 运营对 V2 审核动作无权 → HTTP 403
        ResponseEntity<Map> auditDenied = http.withBasicAuth("dispatcher", "dispatcher123")
                .postForEntity(url("/freight/settlements/audits"),
                        Collections.singletonMap("settlementId", 999), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, auditDenied.getStatusCode());

        // 第三条旧批次：仅延误 L3；按当前生效 V2 生成结算（基础 1740，扣罚 200，应付 1540）
        delayOnlyBatchId = signedBatchWith("V14旧批C", true, false);
        FreightSettlement settlement = settlementService.generate(previewReq(delayOnlyBatchId, null));
        v2SettlementId = settlement.getId();
        assertEquals(2, settlement.getContractVersionNo().intValue());
        assertEquals(0, settlement.getBaseAmount().compareTo(BASE_300));
        assertEquals(0, settlement.getPenaltyAmount().compareTo(new BigDecimal("200.00")));
        assertEquals(0, settlement.getPayableAmount().compareTo(new BigDecimal("1540.00")));
        FreightSettleItem penalty = settlementService.listItems(v2SettlementId).stream()
                .filter(i -> CcConstants.FEE_PENALTY.equals(i.getItemKind())).findFirst()
                .orElseThrow(() -> new AssertionError("应生成延误扣罚项"));
        v2PenaltyItemId = penalty.getId();

        // 运营提出争议 → 待复核、结算单 DISPUTED
        testSubjects.runAs(2L, "dispatcher", () -> {
            SettlementDisputeRequest d = new SettlementDisputeRequest();
            d.setSettlementId(v2SettlementId);
            d.setItemId(v2PenaltyItemId);
            d.setReason("延误为高速封路不可抗力，承运人已提前报备，不应扣罚");
            FreightSettleItem item = settlementService.dispute(d);
            assertEquals(CcConstants.DISPUTE_PENDING, item.getDisputeStatus());
        });
        FreightSettlement disputed = settlementService.requireSettlement(v2SettlementId);
        assertEquals(CcConstants.FST_DISPUTED, disputed.getStatus());
        assertEquals(1, disputed.getDisputePendingCount());
        // 存在待复核争议项，财务审核被拦截
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> settlementService.audit(auditReq(v2SettlementId, "争议未处理试图审核")));
        assertTrue(blocked.getMessage().contains("待复核"));

        // 财务复核：扣罚不计入，应付重算回 1740，争议清零回 PENDING
        testSubjects.runAs(10L, "finance", () -> {
            SettlementReviewRequest r = new SettlementReviewRequest();
            r.setSettlementId(v2SettlementId);
            r.setItemId(v2PenaltyItemId);
            r.setIncluded(false);
            r.setNote("高速封路属不可抗力且有报备记录，该延误扣罚不计入");
            settlementService.review(r);
        });
        FreightSettlement reviewed = settlementService.requireSettlement(v2SettlementId);
        assertEquals(CcConstants.FST_PENDING, reviewed.getStatus());
        assertEquals(0, reviewed.getDisputePendingCount());
        assertEquals(0, reviewed.getPenaltyAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, reviewed.getPayableAmount().compareTo(BASE_300), "扣罚不计入后应付=基础运费");
        FreightSettleItem reviewedItem = settlementService.listItems(v2SettlementId).stream()
                .filter(i -> v2PenaltyItemId.equals(i.getId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(CcConstants.DISPUTE_EXCLUDED, reviewedItem.getDisputeStatus());
        assertFalse(reviewedItem.getIncluded());
        assertEquals(0, reviewedItem.getAmount().compareTo(new BigDecimal("200.00")), "原扣罚金额仍保留可查");

        // 财务审核冻结，随后回放该批次：差异恒 0
        testSubjects.runAs(10L, "finance",
                () -> settlementService.audit(auditReq(v2SettlementId, "复核不计入后审核通过")));
        assertEquals(CcConstants.FST_AUDITED,
                settlementService.requireSettlement(v2SettlementId).getStatus());

        FreightReplayRequest req2 = new FreightReplayRequest();
        req2.setTargetContractId(contractV2Id);
        req2.setBatchIds(Collections.singletonList(delayOnlyBatchId));
        req2.setDefaultBoxType(BOX);
        req2.setDefaultMileageKm(MILEAGE);
        @SuppressWarnings("unchecked")
        Map<String, Object> r2 = replayService.replay(req2);
        @SuppressWarnings("unchecked")
        List<FreightReplayItem> rows2 = (List<FreightReplayItem>) r2.get("items");
        assertEquals(1, rows2.size());
        assertTrue(rows2.get(0).getFrozen());
        assertEquals(0, rows2.get(0).getDiffAmount().compareTo(BigDecimal.ZERO),
                "已审核结算（扣罚已被复核剔除）按冻结单回放，V2 新规则不得重算");
        assertEquals(0, rows2.get(0).getTargetPayable().compareTo(BASE_300));

        // 已审核批次不能重新生成结算
        BusinessException regen = assertThrows(BusinessException.class,
                () -> settlementService.generate(previewReq(delayOnlyBatchId, null)));
        assertTrue(regen.getMessage().contains("已财务审核冻结"));
    }

    // ========== helpers ==========

    /** 造一个已签收批次；可选制造延误 L3 与超温 L1，并全部复核关闭 */
    private long signedBatchWith(String tag, boolean withDelay, boolean withTemp) {
        long nano = System.nanoTime();
        long batchId = shipMinimalBatch(tag, nano);
        List<Long> excIds = new ArrayList<>();
        if (withDelay) {
            ExceptionMarkRequest mark = new ExceptionMarkRequest();
            mark.setBatchId(batchId);
            mark.setExceptionType(CcConstants.EXC_DELAY);
            mark.setDescription(tag + "：高速管制延误2小时");
            mark.setOccurredTime(T0.plusHours(2));
            ExceptionEvent delay = batchService.markException(mark);
            lifecycle.confirm(delay.getId());
            excIds.add(delay.getId());
        }
        if (withTemp) {
            TempReportRequest temp = new TempReportRequest();
            temp.setBatchId(batchId);
            temp.setRecordTime(T0.plusHours(1));
            temp.setTemperature(new BigDecimal("11.50"));
            temp.setRecordedBy("车载温控仪");
            batchService.reportTemperature(temp);
            List<ExceptionEvent> rows = batchService.listExceptions(batchId);
            ExceptionEvent tempExc = rows.stream()
                    .filter(e -> CcConstants.EXC_TEMP.equals(e.getExceptionType()))
                    .findFirst().orElseThrow(AssertionError::new);
            excIds.add(tempExc.getId());
        }
        for (Long excId : excIds) {
            closeException(excId);
        }
        assertEquals(0, lifecycle.unclosedCount(batchId));

        TransportBatch b = batchService.requireBatch(batchId);
        SignRequest sign = new SignRequest();
        sign.setBatchId(batchId);
        sign.setSignBy(tag + "院药库");
        sign.setReceivedApprovalNo(b.getApprovalNoSnap());
        sign.setReceivedPackageSpec(b.getPackageSpecSnap());
        sign.setConfirmQty(b.getShipQty());
        batchService.sign(sign);
        return batchId;
    }

    private void closeException(Long excId) {
        EvidenceAppendRequest ev = new EvidenceAppendRequest();
        ev.setExceptionId(excId);
        ev.setFileName("proof-" + excId + ".txt");
        ev.setFileUrl("/v14/proof/" + excId);
        ev.setNote("V14验收处置证据");
        batchService.appendEvidence(ev);
        Long evidenceId = batchService.listEvidence(excId).get(0).getId();
        lifecycle.conclude(excId, "已处置并评估，申请复核关闭", Collections.singletonList(evidenceId));
        testSubjects.runAs(6L, "qa", () -> lifecycle.review(excId, true, "QA 复核通过关闭"));
    }

    /** 复用 V6 验收的最小造批链路（药品→发货单→车辆→探头→派车→发运），不引用锁价单/路线，费用靠补录箱型里程 */
    private long shipMinimalBatch(String tag, long nano) {
        com.evops.coldchain.dto.DrugCreateRequest dc = new com.evops.coldchain.dto.DrugCreateRequest();
        dc.setDrugName(tag + "-药品-" + nano);
        dc.setApprovalNo("国药准字V14-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugCreate(dc);

        com.evops.coldchain.dto.OrderCreateRequest oc = new com.evops.coldchain.dto.OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg(tag + "医院");
        oc.setShipQty(10);
        Long orderId = orderCreate(oc);

        com.evops.coldchain.dto.VehicleCreateRequest vr = new com.evops.coldchain.dto.VehicleCreateRequest();
        vr.setPlateNo("测14·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName(tag + "物流");
        vr.setDriverName("赵司机");
        Long vehicleId = vehicleCreate(vr);

        com.evops.coldchain.dto.ProbeCreateRequest pc = new com.evops.coldchain.dto.ProbeCreateRequest();
        pc.setSerialNo("SN-V14-" + nano);
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V14-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V14校准证书-" + nano);
        long probeId = probeCreate(pc);

        com.evops.coldchain.dto.ProbeBindRequest pb = new com.evops.coldchain.dto.ProbeBindRequest();
        pb.setRequestId("v14bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeBind(pb);

        com.evops.coldchain.dto.DispatchRequest dr = new com.evops.coldchain.dto.DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        long batchId = dispatch(dr);
        batchService.ship(batchId);
        return batchId;
    }

    // 服务直连（与 V6 用例同风格；无 Shiro 上下文时回退 system 操作者）
    @Autowired private com.evops.coldchain.service.DrugService drugService;
    @Autowired private com.evops.coldchain.service.OrderService orderService;
    @Autowired private com.evops.coldchain.service.VehicleService vehicleService;
    @Autowired private com.evops.coldchain.service.ProbeService probeService;

    private Long drugCreate(com.evops.coldchain.dto.DrugCreateRequest dc) { return drugService.create(dc).getId(); }
    private Long orderCreate(com.evops.coldchain.dto.OrderCreateRequest oc) { return orderService.create(oc).getId(); }
    private Long vehicleCreate(com.evops.coldchain.dto.VehicleCreateRequest vr) { return vehicleService.create(vr).getId(); }
    private Long probeCreate(com.evops.coldchain.dto.ProbeCreateRequest pc) { return probeService.createProbe(pc).getId(); }
    private void probeBind(com.evops.coldchain.dto.ProbeBindRequest pb) { probeService.bindIdempotent(pb); }
    private Long dispatch(com.evops.coldchain.dto.DispatchRequest dr) { return batchService.dispatch(dr).getId(); }

    private SettlementPreviewRequest previewReq(long batchId, Long contractId) {
        SettlementPreviewRequest req = new SettlementPreviewRequest();
        req.setBatchId(batchId);
        req.setContractId(contractId);
        req.setBoxType(BOX);
        req.setMileageKm(MILEAGE);
        return req;
    }

    private SettlementAuditRequest auditReq(long id, String remark) {
        SettlementAuditRequest req = new SettlementAuditRequest();
        req.setSettlementId(id);
        req.setAuditRemark(remark);
        return req;
    }

    private ContractDraftRequest draftBody() {
        ContractDraftRequest body = new ContractDraftRequest();
        body.setVersionName("运输费用按异常扣罚");
        body.setChangeReason("延误/超温/封签异常按等级扣罚");
        List<BaseRateItem> rates = new ArrayList<>();
        rates.add(rate(BOX, 0d, 200d, 6.50, 300d));
        rates.add(rate(BOX, 200d, 500d, 5.80, 1000d));
        rates.add(rate(BOX, 500d, null, 5.20, 2500d));
        body.setBaseRates(rates);
        List<com.evops.coldchain.dto.PenaltyRuleItem> pens = new ArrayList<>();
        pens.add(penalty(CcConstants.EXC_DELAY, CcConstants.GRADE_L1, "PERCENT", null, 20d, 2000d));
        pens.add(penalty(CcConstants.EXC_DELAY, CcConstants.GRADE_L2, "PERCENT", null, 10d, 1000d));
        pens.add(penalty(CcConstants.EXC_DELAY, CcConstants.GRADE_L3, "FIXED", 200d, null, null));
        pens.add(penalty(CcConstants.EXC_TEMP, null, "PERCENT", null, 30d, 5000d));
        pens.add(penalty(CcConstants.EXC_SEAL_BROKEN, null, "FIXED", 500d, null, null));
        body.setPenaltyRules(pens);
        return body;
    }

    private BaseRateItem rate(String box, double from, Double to, double price, double min) {
        BaseRateItem r = new BaseRateItem();
        r.setBoxType(box);
        r.setKmFrom(BigDecimal.valueOf(from));
        r.setKmTo(to == null ? null : BigDecimal.valueOf(to));
        r.setPricePerKm(BigDecimal.valueOf(price));
        r.setMinCharge(BigDecimal.valueOf(min));
        return r;
    }

    private com.evops.coldchain.dto.PenaltyRuleItem penalty(String type, String grade, String calc,
                                                             Double fixed, Double percent, Double cap) {
        com.evops.coldchain.dto.PenaltyRuleItem p = new com.evops.coldchain.dto.PenaltyRuleItem();
        p.setExceptionType(type);
        p.setGrade(grade);
        p.setCalcType(calc);
        if (fixed != null) p.setFixedAmount(BigDecimal.valueOf(fixed));
        if (percent != null) p.setPercent(BigDecimal.valueOf(percent));
        if (cap != null) p.setCapAmount(BigDecimal.valueOf(cap));
        return p;
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/cold-chain" + path;
    }
}
