package com.evops.coldchain;

import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.RegReportAlert;
import com.evops.coldchain.entity.RegReportCaliber;
import com.evops.coldchain.entity.RegReportRun;
import com.evops.coldchain.entity.RegReportVersion;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.RegReportAlertMapper;
import com.evops.coldchain.mapper.RegReportCaliberMapper;
import com.evops.coldchain.mapper.RegReportMapper;
import com.evops.coldchain.mapper.RegReportRunMapper;
import com.evops.coldchain.mapper.RegReportVersionMapper;
import com.evops.coldchain.mapper.ReturnBatchMapper;
import com.evops.coldchain.mapper.TempAlertMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.ExceptionNotifyTransport;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.RegReportCaliberService;
import com.evops.coldchain.service.RegReportService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.evops.coldchain.support.TestSubjects;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输 V16 验收：冷链运输监管报表版本化。
 *
 * 说明：全量套件共享同一内存 H2（Spring 上下文复用），其它测试类可能在当前自然月造批次。
 * 因此精确指标断言全部使用本类专属区域码（V16Z*，其它类不会使用）；监管区域隔离另用
 * 内置授权的 EAST/SOUTH 报表验证可见性。
 *
 * 用例：1) 定时按月生成四指标分桶；2) 缺关键数据待补不填零；3) 口径版本化只影响新版本；
 * 4) 重新计算只追加不覆盖已下载文件，列表/详情/导出/日志交叉核对；5) 区域隔离+权限矩阵；
 * 6) 生成失败告警+错误链路，恢复后重试成功。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegReportVersionedIntegrationTest {

    private static final String BASE = "/api/cold-chain/reports";
    private static final ObjectMapper OM = new ObjectMapper();
    private static final Path EXPORT_DIR = Paths.get("./target/reg-reports-test");

    // 本测试专属区域码（其它测试类不会使用），保证共享内存库下指标桶不被串扰
    private static final String RA = "V16Z1";
    private static final String RB = "V16Z2";
    private static final String RW = "V16Z9";
    private static final String RN = "V16Z3";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;

    @Autowired private RegReportService reportService;
    @Autowired private RegReportCaliberService caliberService;
    @Autowired private TestSubjects testSubjects;

    @Autowired private TransportBatchMapper batchMapper;
    @Autowired private TemperatureLogMapper tempLogMapper;
    @Autowired private TempAlertMapper alertMapper;
    @Autowired private ExceptionEventMapper exceptionMapper;
    @Autowired private ReturnBatchMapper returnMapper;
    @Autowired private RegReportMapper reportMapper;
    @Autowired private RegReportVersionMapper versionMapper;
    @Autowired private RegReportRunMapper runMapper;
    @Autowired private RegReportAlertMapper reportAlertMapper;
    @Autowired private RegReportCaliberMapper caliberMapper;

    @Autowired private DrugService drugService;
    @Autowired private OrderService orderService;
    @Autowired private VehicleService vehicleService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;

    @Autowired
    private ScriptedReportTransport transport;

    private static final String MONTH = YearMonth.now().toString();
    private static final String NEXT_MONTH = YearMonth.now().plusMonths(1).toString();
    private static Long batchA, batchB, batchC;
    private static Long allReportId, aReportId, bReportId, eastReportId, southReportId;
    private static byte[] aV1File;

    /** 每个测试类运行前清理快照目录（含上次失败遗留的目录占用文件），保证用例可重复 */
    @BeforeAll
    static void cleanExportDir() throws Exception {
        if (Files.exists(EXPORT_DIR)) {
            Files.walk(EXPORT_DIR).sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignore) { }
            });
        }
    }

    // ========== 1) 定时按月生成：四指标区域分桶 ==========

    @Test
    @Order(1)
    void scheduledMonthly_generatesMetricsByRegion() {
        batchA = shipBatch("V16A", RA, 10);
        batchB = shipBatch("V16B", RA, 20);
        batchC = shipBatch("V16C", RB, 30);

        insertInRangeLog(batchA);
        insertInRangeLog(batchB);
        insertInRangeLog(batchC);
        insertAlert(batchA, 30);
        insertAlert(batchB, 45);
        insertException(batchA, true);
        insertException(batchB, true);
        insertException(batchB, false);
        insertReturn(batchA, 4);
        insertReturn(batchB, 6);

        // 定时幂等：空区域连扫两次，第二次跳过
        Map<String, Object> scan1 = reportService.generateScheduledIfAbsent(MONTH, RW);
        assertFalse((Boolean) scan1.get("skipped"));
        Map<String, Object> scan2 = reportService.generateScheduledIfAbsent(MONTH, RW);
        assertTrue((Boolean) scan2.get("skipped"));

        RegReportVersion all = reportService.generateScheduled(MONTH, "");
        RegReportVersion va = reportService.generateScheduled(MONTH, RA);
        RegReportVersion vb = reportService.generateScheduled(MONTH, RB);
        allReportId = all.getReportId();
        aReportId = va.getReportId();
        bReportId = vb.getReportId();

        for (RegReportVersion v : new RegReportVersion[]{all, va, vb}) {
            assertEquals(1, v.getVersionNo());
            assertEquals(1, v.getCaliberVersionNo());
            assertEquals(CcConstants.REPORT_GENERATED, v.getStatus());
            assertEquals(CcConstants.REPORT_TRIGGER_SCHEDULED, v.getTriggerSource());
            assertTrue(v.getFileName().endsWith("_v1.csv"));
        }

        // 区域 RA（SHIP_BATCH 按批次计数）
        assertEquals(2L, va.getShipVolume());
        assertEquals(75L, va.getOvertempMinutes());
        assertEquals(3, va.getExceptionTotal());
        assertEquals(2, va.getExceptionClosed());
        assertEquals(0, new BigDecimal("66.67").compareTo(va.getExceptionCloseRate()));
        assertEquals(10L, va.getReturnQty());
        assertEquals(2, va.getReturnCount());

        // 区域 RB：有发运与在温区留痕，超温 0 是真实零；无异常 -> 关闭率 NULL（不填零）
        assertEquals(1L, vb.getShipVolume());
        assertEquals(0L, vb.getOvertempMinutes());
        assertEquals(0, vb.getExceptionTotal());
        assertNull(vb.getExceptionCloseRate());
        assertEquals(0L, vb.getReturnQty());
        assertEquals(0, vb.getReturnCount());

        // 全域桶至少包含本类两区域数据（共享库下其它类数据不做精确断言）
        assertTrue(all.getShipVolume() >= 3L);
        assertTrue(all.getOvertempMinutes() >= 75L);
        assertTrue(all.getReturnQty() >= 10L);

        // 为区域隔离用例准备内置授权区域报表（EAST/SOUTH）
        eastReportId = reportService.generateScheduled(MONTH, "EAST").getReportId();
        southReportId = reportService.generateScheduled(MONTH, "SOUTH").getReportId();
    }

    // ========== 2) 缺关键数据：标记待补，不静默填零 ==========

    @Test
    @Order(2)
    void missingTemperatureData_marksPendingSupplement_notZero() {
        insertDirectShippedBatch("V16-FUTURE", RA, 7, NEXT_MONTH);
        RegReportVersion v = testSubjects.apply(5L, "admin",
                () -> reportService.generateManual(NEXT_MONTH, RA));

        assertEquals(CcConstants.REPORT_PENDING_SUPPLEMENT, v.getStatus());
        assertNull(v.getOvertempMinutes(), "缺温度留痕时超温时长必须为 NULL（待补），不能填 0");
        assertTrue(v.getMissingMetrics().contains(CcConstants.METRIC_OVERTEMP));
        assertNotNull(v.getPendingNote());
        assertTrue(v.getPendingNote().contains("待补"));
        assertEquals(1L, v.getShipVolume());
    }

    // ========== 3) 口径版本化：调整只影响新版本 ==========

    @Test
    @Order(3)
    void caliberVersioning_onlyAffectsNewVersions() {
        RegReportCaliber v2 = testSubjects.apply(5L, "admin", () -> {
            RegReportCaliber draft = caliberService.draft(caliberReq("按件数口径",
                    CcConstants.SHIP_BASIS_QTY, CcConstants.OVERTEMP_SOURCE_ALERT,
                    CcConstants.EXC_CLOSE_BASIS_FOUND, null, "V16验收：发运量改按件数"));
            return caliberService.publish(draft.getId());
        });
        assertEquals(2, v2.getVersionNo());
        assertEquals(CcConstants.EFFECTIVE, v2.getStatus());
        assertEquals(CcConstants.SUPERSEDED,
                caliberMapper.selectOne(new QueryWrapper<RegReportCaliber>().eq("version_no", 1)).getStatus());

        RegReportVersion vaV2 = testSubjects.apply(5L, "admin",
                () -> reportService.generateManual(MONTH, RA));
        assertEquals(2, vaV2.getVersionNo());
        assertEquals(2, vaV2.getCaliberVersionNo());
        assertTrue(vaV2.getCaliberSnapshot().contains("\"shipQtyBasis\":\"SHIP_QTY\""));
        assertEquals(30L, vaV2.getShipVolume());

        RegReportVersion vaV1 = versionMapper.selectOne(new QueryWrapper<RegReportVersion>()
                .eq("report_id", aReportId).eq("version_no", 1));
        assertEquals(1, vaV1.getCaliberVersionNo());
        assertTrue(vaV1.getCaliberSnapshot().contains("\"shipQtyBasis\":\"SHIP_BATCH\""));
        assertEquals(2L, vaV1.getShipVolume());
    }

    // ========== 4) 重新计算不覆盖已下载文件 + 列表/详情/导出/日志交叉核对 ==========

    @Test
    @Order(4)
    void recalcNeverOverwrites_downloadedFile_andCrossCheck() {
        ResponseEntity<byte[]> dl1 = download("admin", "admin123", aReportId, 1);
        aV1File = dl1.getBody();
        assertNotNull(aV1File);
        assertTrue(dl1.getHeaders().getFirst("Content-Disposition").contains("v1.csv"));

        RegReportVersion vaV3 = testSubjects.apply(5L, "admin",
                () -> reportService.generateManual(MONTH, RA));
        assertEquals(3, vaV3.getVersionNo());
        assertTrue(vaV3.getFileName().endsWith("_v3.csv"));

        List<RegReportVersion> versions = versionMapper.selectList(new QueryWrapper<RegReportVersion>()
                .eq("report_id", aReportId).orderByAsc("version_no"));
        assertEquals(3, versions.size());
        assertTrue(versions.get(0).getDownloaded());
        assertEquals("admin", versions.get(0).getDownloadByName());
        assertFalse(versions.get(2).getDownloaded());

        assertArrayEquals(aV1File, readAll(EXPORT_DIR.resolve(versions.get(0).getFileName())));
        assertTrue(Files.exists(EXPORT_DIR.resolve(versions.get(2).getFileName())));

        JsonNode list = get("admin", "admin123", "");
        assertEquals(3, findReport(list, aReportId).path("currentVersion").asInt());

        JsonNode detail = get("admin", "admin123", "/" + aReportId);
        assertEquals(3, detail.path("current").path("versionNo").asInt());
        assertEquals(30, detail.path("current").path("shipVolume").asInt());
        assertEquals(2, findVersion(detail.path("versions"), 1).path("shipVolume").asInt());
        assertTrue(detail.path("runs").size() >= 3);
        boolean hasDownloadLog = false, hasGenerateLog = false;
        for (JsonNode l : detail.path("opLogs")) {
            if ("REG_REPORT_DOWNLOAD".equals(l.path("action").asText())) {
                hasDownloadLog = true;
            }
            if ("REG_REPORT_GENERATE".equals(l.path("action").asText())) {
                hasGenerateLog = true;
            }
        }
        assertTrue(hasGenerateLog, "操作日志应包含生成记录");
        assertTrue(hasDownloadLog, "操作日志应包含下载记录");

        ResponseEntity<byte[]> dl1Again = download("admin", "admin123", aReportId, 1);
        assertArrayEquals(aV1File, dl1Again.getBody());
        String csv = new String(dl1Again.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("# 版本=V1"));
        assertTrue(csv.contains("发运量"));
        assertTrue(csv.contains("异常关闭率"));
        assertTrue(csv.contains("退货数量"));
    }

    // ========== 5) 监管区域隔离 + 权限矩阵 ==========

    @Test
    @Order(5)
    void regionIsolation_andPermissions() {
        JsonNode inspectorList = get("inspector", "inspector123", "");
        List<Long> ids = new ArrayList<>();
        inspectorList.forEach(n -> ids.add(n.path("id").asLong()));
        assertTrue(ids.contains(eastReportId));
        assertFalse(ids.contains(southReportId));
        assertFalse(ids.contains(aReportId));
        assertFalse(ids.contains(allReportId));

        JsonNode cross = getBody("inspector", "inspector123", "/" + southReportId);
        assertFalse(cross.path("success").asBoolean());

        JsonNode globalDenied = post("inspector", "inspector123", "/generate", genBody(MONTH, ""));
        assertFalse(globalDenied.path("success").asBoolean());
        assertTrue(globalDenied.path("message").asText().contains("全域"));
        JsonNode allowed = post("inspector", "inspector123", "/generate", genBody(MONTH, "EAST"));
        assertTrue(allowed.path("success").asBoolean(), allowed.toString());
        JsonNode deniedRegion = post("inspector", "inspector123", "/generate", genBody(MONTH, "SOUTH"));
        assertFalse(deniedRegion.path("success").asBoolean());

        JsonNode inspector2List = get("inspector2", "inspector123", "");
        List<Long> ids2 = new ArrayList<>();
        inspector2List.forEach(n -> ids2.add(n.path("id").asLong()));
        assertTrue(ids2.contains(southReportId));
        assertFalse(ids2.contains(eastReportId));

        assertEquals(403, raw("inspector", "inspector123", HttpMethod.POST, BASE + "/calibers/draft",
                caliberBody()).status);
        assertEquals(403, raw("carrier", "carrier123", HttpMethod.GET, BASE, null).status);
        assertEquals(401, raw(null, null, HttpMethod.GET, BASE, null).status);
        assertEquals(200, raw("admin", "admin123", HttpMethod.GET, BASE, null).status);
        assertEquals(200, raw("admin", "admin123", HttpMethod.GET, BASE + "/calibers", null).status);
        assertEquals(403, raw("carrier", "carrier123", HttpMethod.GET,
                BASE + "/" + eastReportId + "/versions/1/download", null).status);
    }

    // ========== 6) 生成失败告警 + 错误链路，恢复后重试成功 ==========

    @Test
    @Order(6)
    void generateFailure_alertsAndKeepsErrorChain_thenRecover() {
        RegReportCaliber v3 = testSubjects.apply(5L, "admin", () -> {
            RegReportCaliber draft = caliberService.draft(caliberReq("带webhook口径",
                    CcConstants.SHIP_BASIS_BATCH, CcConstants.OVERTEMP_SOURCE_ALERT,
                    CcConstants.EXC_CLOSE_BASIS_FOUND, "http://notify.local/reg-hook", "V16验收失败告警"));
            return caliberService.publish(draft.getId());
        });
        assertEquals(3, v3.getVersionNo());

        // 先删除导出目录（含内容），再在同路径创建一个普通文件占位，使 createDirectories 必失败
        try {
            if (Files.exists(EXPORT_DIR)) {
                Files.walk(EXPORT_DIR).sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignore) { }
                });
            }
            Files.createDirectories(EXPORT_DIR.getParent());
            Files.write(EXPORT_DIR, new byte[]{1});
            assertTrue(Files.isRegularFile(EXPORT_DIR), "失败注入：导出路径必须是普通文件");
        } catch (java.io.IOException ioe) {
            throw new RuntimeException(ioe);
        }
        transport.failuresRemaining = 1;

        boolean thrown = false;
        try {
            testSubjects.runAs(5L, "admin", () -> reportService.generateManual(MONTH, RN));
        } catch (RuntimeException e) {
            thrown = true;
            assertTrue(e.getMessage().contains("生成失败"));
        }
        assertTrue(thrown, "生成失败必须向上抛出并提示");

        com.evops.coldchain.entity.RegReport northReport =
                reportMapper.selectOne(new QueryWrapper<com.evops.coldchain.entity.RegReport>()
                        .eq("report_month", MONTH).eq("supervise_region", RN));
        assertNotNull(northReport);
        assertEquals(CcConstants.REPORT_FAILED, northReport.getLatestStatus());
        List<RegReportRun> failedRuns = runMapper.selectList(new QueryWrapper<RegReportRun>()
                .eq("report_id", northReport.getId()).eq("run_status", "FAILED").orderByDesc("id"));
        assertFalse(failedRuns.isEmpty());
        RegReportRun failed = failedRuns.get(0);
        assertNotNull(failed.getErrorType());
        assertNotNull(failed.getErrorMessage());
        assertTrue(failed.getErrorChain().contains("THROWN") || failed.getErrorChain().contains("ROOT"));
        assertNotNull(failed.getErrorStack(), "必须保留完整堆栈");

        List<RegReportAlert> alerts = reportAlertMapper.selectList(new QueryWrapper<RegReportAlert>()
                .eq("run_id", failed.getId()));
        assertEquals(1, alerts.size());
        assertEquals("WEBHOOK", alerts.get(0).getChannel());
        assertEquals("FAILED", alerts.get(0).getSendStatus());
        assertTrue(alerts.get(0).getFailReason().contains("模拟投递失败"));
        assertEquals(0, versionMapper.selectCount(new QueryWrapper<RegReportVersion>()
                .eq("report_id", northReport.getId())).intValue());

        try {
            Files.deleteIfExists(EXPORT_DIR);
        } catch (java.io.IOException ioe) {
            throw new RuntimeException(ioe);
        }
        RegReportVersion northV1 = testSubjects.apply(5L, "admin",
                () -> reportService.generateManual(MONTH, RN));
        assertEquals(1, northV1.getVersionNo());
        assertEquals(CcConstants.REPORT_GENERATED, northV1.getStatus());

        List<RegReportRun> allRuns = runMapper.selectList(new QueryWrapper<RegReportRun>()
                .eq("report_id", northReport.getId()).orderByAsc("id"));
        assertTrue(allRuns.size() >= 2);
        assertEquals("FAILED", allRuns.get(0).getRunStatus());
        assertEquals("SUCCESS", allRuns.get(allRuns.size() - 1).getRunStatus());
    }

    // ==================== 造数辅助 ====================

    private long shipBatch(String tag, String region, int qty) {
        long nano = System.nanoTime();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName(tag + "-药品-" + nano);
        dc.setApprovalNo("国药准字V16-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg(tag + "医院");
        oc.setShipQty(qty);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测16·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName(tag + "物流");
        vr.setDriverName("孙司机");
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V16-" + nano);
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V16-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V16校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v16bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        dr.setSuperviseRegion(region);
        long batchId = batchService.dispatch(dr).getId();
        batchService.ship(batchId);
        return batchId;
    }

    private void insertDirectShippedBatch(String tag, String region, int qty, String month) {
        LocalDateTime t = YearMonth.parse(month).atDay(15).atTime(10, 0);
        TransportBatch b = new TransportBatch();
        b.setBatchNo(tag + "-" + System.nanoTime());
        b.setOrderId(1L);
        b.setVehicleId(1L);
        b.setDrugId(1L);
        b.setDrugVersionNo(1);
        b.setApprovalNoSnap("国药准字DIRECT");
        b.setTempMinSnap(new BigDecimal("2.00"));
        b.setTempMaxSnap(new BigDecimal("8.00"));
        b.setPackageSpecSnap("1支/盒");
        b.setReceiverOrg(tag + "医院");
        b.setShipQty(qty);
        b.setStatus(CcConstants.BATCH_SHIPPED);
        b.setShipTime(t);
        b.setSuperviseRegion(region);
        batchMapper.insert(b);
    }

    private void insertInRangeLog(Long batchId) {
        TemperatureLog l = new TemperatureLog();
        l.setBatchId(batchId);
        l.setRecordTime(LocalDateTime.now());
        l.setTemperature(new BigDecimal("5.00"));
        l.setInRange(true);
        l.setRecordedBy("车载温控仪");
        tempLogMapper.insert(l);
    }

    private void insertAlert(Long batchId, long minutes) {
        TempAlert a = new TempAlert();
        a.setBatchId(batchId);
        a.setZoneCode("REFRIGERATE");
        a.setZoneName("冷藏温区");
        a.setTempMinSnap(new BigDecimal("2.00"));
        a.setTempMaxSnap(new BigDecimal("8.00"));
        a.setTolerateMinutes(30);
        a.setJitterGapMinutes(10);
        LocalDateTime now = LocalDateTime.now();
        a.setStartTime(now.minusMinutes(minutes));
        a.setTriggerTime(now);
        a.setLastExceedTime(now);
        a.setDurationMinutes(minutes);
        a.setDescription("V16验收超温 " + minutes + " 分钟");
        a.setStatus("OPEN");
        alertMapper.insert(a);
    }

    private void insertException(Long batchId, boolean closed) {
        ExceptionEvent e = new ExceptionEvent();
        e.setBatchId(batchId);
        e.setExceptionType(CcConstants.EXC_DELAY);
        e.setDescription("V16验收延误异常 closed=" + closed);
        LocalDateTime now = LocalDateTime.now();
        e.setOccurredTime(now);
        e.setFoundTime(now);
        if (closed) {
            e.setStatus(CcConstants.EXC_RESOLVED);
            e.setResolveTime(now);
        } else {
            e.setStatus(CcConstants.EXC_CONFIRMED);
        }
        exceptionMapper.insert(e);
    }

    private void insertReturn(Long batchId, int qty) {
        ReturnBatch r = new ReturnBatch();
        r.setReturnNo("TH-V16-" + System.nanoTime());
        r.setSourceBatchId(batchId);
        TransportBatch b = batchMapper.selectById(batchId);
        r.setSourceBatchNo(b == null ? "-" : b.getBatchNo());
        r.setReasonType(CcConstants.RETURN_REASON_REJECTION);
        r.setReasonDetail("V16验收退货");
        r.setReturnQty(qty);
        r.setStatus(CcConstants.RETURN_REGISTERED);
        returnMapper.insert(r);
    }

    private com.evops.coldchain.dto.RegCaliberDraftRequest caliberReq(
            String name, String shipBasis, String overSrc, String excBasis, String endpoint, String reason) {
        com.evops.coldchain.dto.RegCaliberDraftRequest req =
                new com.evops.coldchain.dto.RegCaliberDraftRequest();
        req.setVersionName(name);
        req.setShipQtyBasis(shipBasis);
        req.setOvertempSource(overSrc);
        req.setExceptionCloseBasis(excBasis);
        req.setReturnBasis(CcConstants.RETURN_BASIS_REGISTER);
        req.setIncludeRevoked(false);
        req.setNotifyEndpoint(endpoint);
        req.setChangeReason(reason);
        return req;
    }

    private static byte[] readAll(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== HTTP 辅助 ====================

    private Map<String, Object> genBody(String month, String region) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportMonth", month);
        m.put("superviseRegion", region);
        return m;
    }

    private Map<String, Object> caliberBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionName", "越权版");
        m.put("shipQtyBasis", "SHIP_BATCH");
        m.put("overtempSource", "TEMP_ALERT");
        m.put("exceptionCloseBasis", "FOUND_MONTH");
        m.put("returnBasis", "REGISTER_MONTH");
        m.put("includeRevoked", false);
        m.put("changeReason", "x");
        return m;
    }

    private JsonNode findReport(JsonNode arr, long id) {
        for (JsonNode n : arr) {
            if (n.path("id").asLong() == id) {
                return n;
            }
        }
        throw new AssertionError("列表中缺少报表 " + id);
    }

    private JsonNode findVersion(JsonNode arr, int versionNo) {
        for (JsonNode n : arr) {
            if (n.path("versionNo").asInt() == versionNo) {
                return n;
            }
        }
        throw new AssertionError("缺少版本 V" + versionNo);
    }

    private JsonNode post(String user, String pw, String path, Object body) {
        HttpResp resp = raw(user, pw, HttpMethod.POST, BASE + path, body);
        assertEquals(200, resp.status, "POST " + path + " -> " + resp.status + " " + resp.body);
        return readTree(resp.body);
    }

    private JsonNode get(String user, String pw, String path) {
        JsonNode env = getBody(user, pw, path);
        assertTrue(env.path("success").asBoolean(), env.toString());
        return env.path("data");
    }

    private JsonNode getBody(String user, String pw, String path) {
        HttpResp resp = raw(user, pw, HttpMethod.GET, BASE + path, null);
        assertEquals(200, resp.status, "GET " + path + " -> " + resp.status + " " + resp.body);
        return readTree(resp.body);
    }

    private ResponseEntity<byte[]> download(String user, String pw, Long reportId, int versionNo) {
        return http.withBasicAuth(user, pw).exchange(
                "http://localhost:" + port + BASE + "/" + reportId + "/versions/" + versionNo + "/download",
                HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
    }

    private HttpResp raw(String user, String pw, HttpMethod method, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            TestRestTemplate client = user == null ? http : http.withBasicAuth(user, pw);
            ResponseEntity<String> resp = client.exchange(
                    "http://localhost:" + port + path, method, entity, String.class);
            return new HttpResp(resp.getStatusCodeValue(), resp.getBody());
        } catch (HttpClientErrorException e) {
            return new HttpResp(e.getRawStatusCode(), e.getResponseBodyAsString());
        }
    }

    private static JsonNode readTree(String body) {
        try {
            return OM.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON: " + body, e);
        }
    }

    private static class HttpResp {
        final int status;
        final String body;

        HttpResp(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    // ==================== 可控通知通道替身 ====================

    @TestConfiguration
    static class ScriptedTransportConfig {
        @Bean
        @Primary
        public ExceptionNotifyTransport scriptedRegReportTransport() {
            return new ScriptedReportTransport();
        }
    }

    static class ScriptedReportTransport implements ExceptionNotifyTransport {
        int failuresRemaining = 0;

        @Override
        public synchronized SendResult send(String endpoint, String payload) {
            if (failuresRemaining > 0) {
                failuresRemaining--;
                return SendResult.fail("模拟投递失败：对端未响应");
            }
            return SendResult.ok();
        }
    }
}
