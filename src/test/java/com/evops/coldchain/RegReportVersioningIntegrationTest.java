package com.evops.coldchain;

import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.RegReport;
import com.evops.coldchain.entity.RegReportCaliber;
import com.evops.coldchain.entity.RegReportDownload;
import com.evops.coldchain.entity.RegReportError;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.TempAlert;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.mapper.RegReportCaliberMapper;
import com.evops.coldchain.mapper.RegReportDownloadMapper;
import com.evops.coldchain.mapper.RegReportErrorMapper;
import com.evops.coldchain.mapper.RegReportMapper;
import com.evops.coldchain.mapper.ReturnBatchMapper;
import com.evops.coldchain.mapper.TempAlertMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.ExceptionNotifyTransport;
import com.evops.coldchain.service.RegReportComputeService;
import com.evops.coldchain.service.RegReportGenerateService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输 V16 验收：冷链运输监管报表版本化。
 *
 * 验收不只看详情，而是交叉核对“列表 / 详情 / 导出 / 操作日志”四口径一致：
 * 1) 统计口径版本化（起草/发布热替换，报表绑定生成时生效口径；口径调整只影响此后报表）；
 * 2) Quartz 定时按月幂等生成首版（已有版本跳过，不重复不覆盖）；
 * 3) 按月汇总发运量/超温时长/异常关闭率/退货数量（真零 vs 待补）；
 * 4) 缺关键数据标待补（不静默填零）；
 * 5) 重算只产生同月下一版本，绝不覆盖旧版本/已下载文件（文件名含版本号，旧版内容 SHA 不变）；
 * 6) 生成失败落错误链（阶段/类型/消息/堆栈）并 webhook 告警（投递失败也留痕）；
 * 7) Shiro 权限矩阵（401/403/200）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegReportVersioningIntegrationTest {

    private static final String BASE = "/api/cold-chain/reg-reports";
    private static final ObjectMapper OM = new ObjectMapper();

    private static final YearMonth M_SCHED = YearMonth.of(2026, 5);
    private static final YearMonth M_SUCCESS = YearMonth.of(2026, 6);
    private static final YearMonth M_PENDING = YearMonth.of(2026, 7);
    private static final YearMonth M_FAIL = YearMonth.of(2026, 4);
    private static final YearMonth M_NO_EFFECT = YearMonth.of(2026, 3);

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate http;

    @Autowired private RegReportGenerateService generateService;
    @Autowired private RegReportCaliberMapper caliberMapper;
    @Autowired private RegReportMapper reportMapper;
    @Autowired private RegReportErrorMapper errorMapper;
    @Autowired private RegReportDownloadMapper downloadMapper;
    @Autowired private TransportBatchMapper batchMapper;
    @Autowired private TempAlertMapper alertMapper;
    @Autowired private com.evops.coldchain.mapper.ExceptionEventMapper exceptionMapper;
    @Autowired private ReturnBatchMapper returnBatchMapper;
    @Autowired private OpLogMapper opLogMapper;
    @Autowired private FaultyCompute faultyCompute;
    @Autowired private ScriptedRegTransport transport;
    @Autowired private org.quartz.Scheduler quartzScheduler;

    private static Long juneV1Id;
    private static String juneV1Sha;
    private static String juneV1File;
    private static long uniq = System.nanoTime();
    private static int seq = 0;

    // ========== 1) 口径版本化 + Quartz 定时幂等 ==========

    @Test
    @Order(1)
    void caliberVersioning_and_scheduledIdempotent() throws Exception {
        RegReportCaliber v1 = caliberMapper.selectOne(new QueryWrapper<RegReportCaliber>()
                .eq("version_no", 1));
        assertEquals(CcConstants.EFFECTIVE, v1.getStatus());
        assertEquals(0, v1.getCloseWindowDays());

        // 调度/监管无口径维护权、无生成权 → 403
        assertEquals(403, raw("dispatcher", "dispatcher123", HttpMethod.POST,
                BASE + "/calibers/draft", caliberBody("越权口径", 3)).status);
        assertEquals(403, raw("inspector", "inspector123", HttpMethod.POST,
                BASE + "/generate", monthBody(M_SCHED)).status);

        // Quartz 定时（直连服务，等价月度 Job）：M_SCHED 无版本 -> 生成 V1，绑定当时生效口径 V1
        RegReportGenerateService.GenerateOutcome o1 = generateService.generateScheduled(M_SCHED);
        assertTrue(o1.created);
        assertEquals(1, o1.report.getVersionNo());
        assertEquals(CcConstants.TRIGGER_SCHEDULED, o1.report.getTriggerSource());
        assertEquals(1, o1.report.getCaliberVersionNo());
        // 空月（无台账活动）= 真零，数据齐备而非待补
        assertEquals(CcConstants.REPORT_SUCCESS, o1.report.getStatus());
        assertEquals(0, o1.report.getShippedBatchCount());
        assertEquals(0L, o1.report.getOvertempMinutes());
        assertEquals(100, o1.report.getExceptionCloseRate().doubleValue(), 0.001);

        // 定时幂等：再次触发同月 -> 跳过，不产生新版本/不覆盖
        RegReportGenerateService.GenerateOutcome o2 = generateService.generateScheduled(M_SCHED);
        assertTrue(o2.skipped);
        assertEquals(1, reportMapper.selectCount(new QueryWrapper<RegReport>()
                .eq("stat_month", M_SCHED.toString())).intValue());

        // —— 真实 Quartz 执行链：注册并触发 RegReportMonthlyJob（JobDataMap 指定 2026-08），
        // 验证 Quartz JobFactory 的 Spring 注入、Job 运行、定时触发标记与幂等 ——
        runQuartzMonthlyJob("2026-08");
        RegReport aug = awaitReport("2026-08");
        assertEquals(1, aug.getVersionNo());
        assertEquals(CcConstants.TRIGGER_SCHEDULED, aug.getTriggerSource(), "Quartz 触发必须记为定时来源");
        runQuartzMonthlyJob("2026-08");
        Thread.sleep(500); // 等第二次触发完成（异步），断言仍只有一版
        assertEquals(1, reportMapper.selectCount(new QueryWrapper<RegReport>()
                .eq("stat_month", "2026-08")).intValue(), "Quartz 重复触发必须幂等不覆盖");

        // 财务发布口径 V2（异常关闭期限 7 天）：V1 被热替换
        JsonNode draft = post("finance", "finance123", "/calibers/draft", caliberBody("关闭期限口径", 7));
        assertTrue(draft.path("success").asBoolean(), draft.toString());
        long v2Id = draft.path("data").path("id").asLong();
        assertEquals(2, draft.path("data").path("versionNo").asInt());
        JsonNode pub = post("finance", "finance123", "/calibers/" + v2Id + "/publish", null);
        assertTrue(pub.path("success").asBoolean(), pub.toString());
        assertEquals(CcConstants.SUPERSEDED, caliberMapper.selectOne(new QueryWrapper<RegReportCaliber>()
                .eq("version_no", 1)).getStatus());
        // 已生成的 5 月报表仍绑定 V1（口径调整不影响历史报表）
        assertEquals(1, generateService.latestVersion(M_SCHED.toString()).getCaliberVersionNo());
    }

    // ========== 2) 四指标按月汇总（绑定 V2，关闭期限 7 天）+ 3) 列表/详情/导出/日志交叉核对 ==========

    @Test
    @Order(2)
    void generate_success_fourMetrics_crossCheckListDetailExportLog() {
        buildJuneSuccessData();

        JsonNode gen = post("finance", "finance123", "/generate", monthBody(M_SUCCESS));
        assertTrue(gen.path("success").asBoolean(), gen.toString());
        JsonNode r1 = gen.path("data");
        juneV1Id = r1.path("id").asLong();
        assertEquals(1, r1.path("versionNo").asInt());
        assertEquals(CcConstants.REPORT_SUCCESS, r1.path("status").asText());
        assertEquals(2, r1.path("caliberVersionNo").asInt(), "6 月报表应绑定新发布口径 V2");
        assertEquals(CcConstants.TRIGGER_MANUAL, r1.path("triggerSource").asText());

        // —— 列表口径 ——
        JsonNode list = get("inspector", "inspector123", "?statMonth=" + M_SUCCESS);
        assertEquals(1, list.size(), "6 月当前仅一版");
        JsonNode listRow = list.get(0);
        assertEquals(150, listRow.path("shipQtyTotal").asInt());
        assertEquals(2, listRow.path("shippedBatchCount").asInt());
        assertEquals(75, listRow.path("overtempMinutes").asLong());
        assertEquals(3, listRow.path("exceptionTotalCount").asInt());
        assertEquals(1, listRow.path("exceptionClosedCount").asInt());
        assertEquals(33.33, listRow.path("exceptionCloseRate").asDouble(), 0.001);
        assertEquals(25, listRow.path("returnQtyTotal").asInt());
        assertEquals("", listRow.path("pendingMetrics").asText());

        // —— 详情口径（与列表同读快照；导出前无下载留痕）——
        JsonNode detail = get("dispatcher", "dispatcher123", "/" + juneV1Id);
        JsonNode snap = detail.path("report");
        assertEquals(juneV1Id.intValue(), snap.path("id").asInt());
        assertEquals(150, snap.path("shipQtyTotal").asInt());
        assertEquals(75, snap.path("overtempMinutes").asLong());
        assertEquals(33.33, snap.path("exceptionCloseRate").asDouble(), 0.001);
        assertTrue(snap.path("caliberSnapshot").asText().contains("\"closeWindowDays\":7"));
        assertEquals(0, detail.path("pendingMetricList").size());
        assertEquals(0, detail.path("downloads").size());
        assertEquals(0, detail.path("errors").size(), "成功月无错误链");

        // —— 导出口径（HTTP 取字节按 UTF-8 解析；两次导出同版本内容/SHA/文件名一致）——
        ExportHttp x1 = exportCsv("inspector", "inspector123", juneV1Id);
        ExportHttp x2 = exportCsv("inspector", "inspector123", juneV1Id);
        assertEquals(200, x1.status);
        assertTrue(x1.disposition.contains("reg_report_2026-06_v1_"));
        assertTrue(x1.disposition.endsWith(".csv\""));
        for (String body : new String[]{x1.body, x2.body}) {
            assertTrue(body.contains("冷链运输监管报表"));
            assertTrue(body.contains("发运量-发运数量") && body.contains("150"));
            assertTrue(body.contains("发运量-批次数") && body.contains("2"));
            assertTrue(body.contains("超温时长") && body.contains("75"));
            assertTrue(body.contains("异常关闭率") && body.contains("33.33"));
            assertTrue(body.contains("退货数量") && body.contains("25"));
            // 表头含“待补原因”列名属正常；数据齐备时任何指标的数据状态都不能是“待补”单元格
            assertFalse(body.contains(",待补,"), "数据齐备不应出现待补指标行");
        }

        // 详情回读：两条下载留痕，SHA-256 相同、文件名相同（同版本重复导出）
        JsonNode detailAfter = get("inspector", "inspector123", "/" + juneV1Id);
        assertEquals(2, detailAfter.path("downloads").size());
        String sha1 = detailAfter.path("downloads").get(0).path("contentSha256").asText();
        String sha2 = detailAfter.path("downloads").get(1).path("contentSha256").asText();
        assertEquals(sha1, sha2, "同一不可变版本重复导出内容摘要必须一致");
        juneV1Sha = sha1;
        juneV1File = detailAfter.path("downloads").get(0).path("fileName").asText();
        assertTrue(juneV1File.contains("_v1_"));
        assertEquals("inspector", detailAfter.path("downloads").get(0).path("operator").asText());

        // —— 操作日志口径：生成 1 条 + 导出 2 条，均挂报表ID，摘要含编号/版本/SHA ——
        List<OpLog> genLogs = opLogMapper.selectList(new QueryWrapper<OpLog>()
                .eq("entity_type", CcConstants.ENT_REG_REPORT)
                .eq("entity_id", juneV1Id)
                .eq("action", CcConstants.ACT_REG_REPORT_GENERATE));
        assertEquals(1, genLogs.size());
        assertTrue(genLogs.get(0).getSummary().contains("2026-06 V1"));
        assertTrue(genLogs.get(0).getSummary().contains("数据齐备"));
        List<OpLog> exportLogs = opLogMapper.selectList(new QueryWrapper<OpLog>()
                .eq("entity_type", CcConstants.ENT_REG_REPORT)
                .eq("entity_id", juneV1Id)
                .eq("action", CcConstants.ACT_REG_REPORT_EXPORT));
        assertEquals(2, exportLogs.size());
        assertTrue(exportLogs.get(0).getSummary().contains(juneV1Sha.substring(0, 12)));
        // 导出日志操作者列记录实际下载人（与下载留痕 operator 一致）
        assertEquals("inspector", exportLogs.get(0).getOperator());
        assertEquals("inspector", exportLogs.get(1).getOperator());
    }

    // ========== 4) 重算只出新版本，不覆盖旧版本/已下载文件 ==========

    @Test
    @Order(3)
    void recalculate_createsNewVersion_neverOverwritesDownloaded() {
        // 6 月补发一批（30 件）后人工重算
        insertShippedBatch(M_SUCCESS, 15, 30);
        JsonNode gen = post("finance", "finance123", "/generate", monthBody(M_SUCCESS));
        assertTrue(gen.path("success").asBoolean(), gen.toString());
        JsonNode v2 = gen.path("data");
        assertEquals(2, v2.path("versionNo").asInt());
        assertEquals(3, v2.path("shippedBatchCount").asInt());
        assertEquals(180, v2.path("shipQtyTotal").asInt(), "新版本反映补后的发运量");
        assertNotSameId(v2.path("id").asLong(), juneV1Id);

        // 列表有两版，V2 在前；旧版仍在
        JsonNode list = get("finance", "finance123", "?statMonth=" + M_SUCCESS);
        assertEquals(2, list.size());
        assertEquals(2, list.get(0).path("versionNo").asInt());
        assertEquals(1, list.get(1).path("versionNo").asInt());

        // V1 快照原封不动（仍为 2 批/150 件）
        JsonNode v1Detail = get("finance", "finance123", "/" + juneV1Id);
        assertEquals(150, v1Detail.path("report").path("shipQtyTotal").asInt());
        assertEquals(2, v1Detail.path("report").path("shippedBatchCount").asInt());

        // 再导出 V1：文件名/SHA 与首次完全一致，旧已下载文件不被覆盖；V2 文件名不同
        ExportHttp v1Again = exportCsv("finance", "finance123", juneV1Id);
        assertTrue(v1Again.disposition.contains(juneV1File.replace(".csv", ""))
                || v1Again.disposition.contains("reg_report_2026-06_v1_"));
        JsonNode v1Downloads = get("finance", "finance123", "/" + juneV1Id).path("downloads");
        for (JsonNode d : v1Downloads) {
            assertEquals(juneV1Sha, d.path("contentSha256").asText(), "V1 内容始终不变");
            assertEquals(juneV1File, d.path("fileName").asText());
        }
        long v2Id = v2.path("id").asLong();
        ExportHttp v2Export = exportCsv("finance", "finance123", v2Id);
        assertTrue(v2Export.disposition.contains("reg_report_2026-06_v2_"));
        assertFalse(v2Export.disposition.contains("_v1_"));
    }

    // ========== 5) 缺关键数据：标待补而非静默填零 ==========

    @Test
    @Order(4)
    void missingKeyData_markedPending_notSilentZero() {
        // 7 月：1 批发运 10 件；1 起“无温控预警背书”的温度超限异常（确有超温却无时长）；无预警；无退货
        insertShippedBatch(M_PENDING, 8, 10);
        insertTempException(M_PENDING, 9, true, null);   // alertId=null：无连续超限时长

        JsonNode gen = post("finance", "finance123", "/generate", monthBody(M_PENDING));
        assertTrue(gen.path("success").asBoolean(), gen.toString());
        JsonNode r = gen.path("data");
        assertEquals(CcConstants.REPORT_PENDING, r.path("status").asText());
        assertNull(orNull(r.path("overtempMinutes")), "超温时长必须留空待补，不能静默填 0");
        // 其余指标不受影响，正常给出
        assertEquals(1, r.path("shippedBatchCount").asInt());
        assertEquals(10, r.path("shipQtyTotal").asInt());
        assertEquals(1, r.path("exceptionTotalCount").asInt());
        assertEquals(1, r.path("exceptionClosedCount").asInt());
        assertEquals(100, r.path("exceptionCloseRate").asDouble(), 0.001);
        assertEquals(0, r.path("returnQtyTotal").asInt());
        assertTrue(r.path("pendingMetrics").asText().contains(CcConstants.REG_METRIC_OVERTEMP));

        JsonNode detail = get("inspector", "inspector123", "/" + r.path("id").asLong());
        assertEquals(1, detail.path("pendingMetricList").size());
        assertEquals(CcConstants.REG_METRIC_OVERTEMP, detail.path("pendingMetricList").get(0).asText());
        String reason = detail.path("supplementReasons").path(CcConstants.REG_METRIC_OVERTEMP).asText();
        assertTrue(reason.contains("背书") && reason.contains("异常#"), reason);

        // 导出口径：超温时长显式“待补”并给原因，不出现 0 冒充
        ExportHttp x = exportCsv("inspector", "inspector123", r.path("id").asLong());
        assertTrue(x.body.contains("超温时长"));
        assertTrue(x.body.contains("待补"));
        assertTrue(x.body.contains("背书"));
        // 发运量仍展示真实 10，未被连带清零
        assertTrue(x.body.contains("10"));
    }

    // ========== 6) 生成失败：错误链 + 告警（含投递失败留痕） ==========

    @Test
    @Order(5)
    void generateFailure_errorChainAndAlert() {
        // 给生效口径配 webhook
        RegReportCaliber v2 = caliberMapper.selectOne(new QueryWrapper<RegReportCaliber>()
                .eq("version_no", 2));
        v2.setNotifyEndpoint("http://reg-alert.local/hook");
        caliberMapper.updateById(v2);

        // 计算阶段抛错 + webhook 首次投递失败：生成失败、不落报表行、错误链记录告警 FAILED
        faultyCompute.failuresRemaining = 1;
        transport.failuresRemaining = 1;
        JsonNode failed = post("finance", "finance123", "/generate", monthBody(M_FAIL));
        assertFalse(failed.path("success").asBoolean());
        assertTrue(failed.path("message").asText().contains("错误链"), failed.path("message").asText());
        assertTrue(failed.path("message").asText().contains("COMPUTE"));

        List<RegReportError> errors = generateService.listErrors(M_FAIL.toString());
        assertEquals(1, errors.size());
        RegReportError e = errors.get(0);
        assertEquals("COMPUTE", e.getFailStage());
        assertEquals(CcConstants.TRIGGER_MANUAL, e.getTriggerSource());
        assertEquals(2, e.getCaliberVersionNo().intValue());
        assertEquals(CcConstants.NOTIFY_FAILED, e.getAlertStatus());
        assertTrue(e.getAlertFailReason().contains("模拟投递失败"));
        assertEquals("http://reg-alert.local/hook", e.getAlertTarget());
        assertTrue(e.getErrorMessage().contains("数据源不可用"));
        assertTrue(e.getStackTrace().contains("com.evops"), "错误链保留堆栈链路");
        assertEquals(0, reportMapper.selectCount(new QueryWrapper<RegReport>()
                .eq("stat_month", M_FAIL.toString())).intValue(), "失败不得产生可下载报表行");

        // 失败动作写操作日志
        List<OpLog> failLogs = opLogMapper.selectList(new QueryWrapper<OpLog>()
                .eq("entity_type", CcConstants.ENT_REG_REPORT)
                .eq("action", CcConstants.ACT_REG_REPORT_FAIL));
        assertTrue(failLogs.stream().anyMatch(l -> l.getSummary().contains("2026-04")
                && l.getSummary().contains("错误链#" + e.getId())));

        // 恢复后重算成功（同月只生成一版；错误链仍保留 1 条）
        faultyCompute.failuresRemaining = 0;
        transport.failuresRemaining = 0;
        JsonNode retry = post("finance", "finance123", "/generate", monthBody(M_FAIL));
        assertTrue(retry.path("success").asBoolean(), retry.toString());
        assertEquals(1, reportMapper.selectCount(new QueryWrapper<RegReport>()
                .eq("stat_month", M_FAIL.toString())).intValue());
        assertEquals(1, generateService.listErrors(M_FAIL.toString()).size(), "历史错误链不被删除");

        // 无生效口径（LOAD_CALIBER）：失败 + 站内告警（SITE，无 webhook 目标）
        for (RegReportCaliber c : caliberMapper.selectList(new QueryWrapper<RegReportCaliber>())) {
            c.setStatus(CcConstants.SUPERSEDED);
            caliberMapper.updateById(c);
        }
        JsonNode noEff = post("finance", "finance123", "/generate", monthBody(M_NO_EFFECT));
        assertFalse(noEff.path("success").asBoolean());
        RegReportError ne = generateService.listErrors(M_NO_EFFECT.toString()).get(0);
        assertEquals("LOAD_CALIBER", ne.getFailStage());
        assertEquals("SITE", ne.getAlertStatus());
        assertNull(ne.getCaliberVersionNo());
        assertNull(ne.getAlertTarget());
        // 还原 V2 生效，便于后续用例
        v2.setStatus(CcConstants.EFFECTIVE);
        caliberMapper.updateById(v2);
    }

    // ========== 7) 权限矩阵 ==========

    @Test
    @Order(6)
    void permissions_matrix() {
        // 未认证 -> 401
        assertEquals(401, raw(null, null, HttpMethod.GET, BASE, null).status);
        // 无监管报表权限岗位 -> 403
        assertEquals(403, raw("receiver", "receiver123", HttpMethod.GET, BASE, null).status);
        assertEquals(403, raw("carrier", "carrier123", HttpMethod.GET, BASE, null).status);
        // 监管岗：可查看/导出，不能维护口径/生成
        assertEquals(200, raw("inspector", "inspector123", HttpMethod.GET, BASE, null).status);
        assertEquals(200, raw("inspector", "inspector123", HttpMethod.GET,
                BASE + "/" + juneV1Id + "/export", null).status);
        assertEquals(403, raw("inspector", "inspector123", HttpMethod.POST,
                BASE + "/calibers/draft", caliberBody("x", 1)).status);
        assertEquals(403, raw("inspector", "inspector123", HttpMethod.POST,
                BASE + "/generate", monthBody(M_SUCCESS)).status);
        // 调度/QA：可查看；不能导出/维护/生成
        assertEquals(200, raw("dispatcher", "dispatcher123", HttpMethod.GET, BASE, null).status);
        assertEquals(200, raw("qa", "qa123", HttpMethod.GET, BASE, null).status);
        assertEquals(403, raw("dispatcher", "dispatcher123", HttpMethod.GET,
                BASE + "/" + juneV1Id + "/export", null).status);
        assertEquals(403, raw("qa", "qa123", HttpMethod.POST,
                BASE + "/generate", monthBody(M_SUCCESS)).status);
        // 财务：报送归口，全套 200
        assertEquals(200, raw("finance", "finance123", HttpMethod.GET, BASE, null).status);
        assertEquals(200, raw("finance", "finance123", HttpMethod.GET,
                BASE + "/calibers", null).status);
        assertEquals(200, raw("finance", "finance123", HttpMethod.POST,
                BASE + "/calibers/draft", caliberBody("财务口径V3", 0)).status);
        // 管理员：通配权限
        assertEquals(200, raw("admin", "admin123", HttpMethod.GET, BASE, null).status);
        assertEquals(200, raw("admin", "admin123", HttpMethod.POST,
                BASE + "/generate", monthBody(YearMonth.of(2026, 2))).status);
    }

    // ==================== 造数 ====================

    /** 6 月齐备数据：2 批发运 100+50；2 条预警 30+45 分钟；3 起异常（期限7天内关闭1）；2 笔退货 20+5 */
    private void buildJuneSuccessData() {
        insertShippedBatch(M_SUCCESS, 3, 100);
        insertShippedBatch(M_SUCCESS, 20, 50);
        long b1 = anyBatch(M_SUCCESS, 3);
        insertAlert(M_SUCCESS, 4, b1, 30);
        long b2 = anyBatch(M_SUCCESS, 20);
        insertAlert(M_SUCCESS, 21, b2, 45);
        // 异常（occurred 在 6 月；V2 口径关闭期限 7 天）
        insertException(M_SUCCESS, 5, CcConstants.EXC_DELAY, true, 1);   // 1 天内关闭 -> 计入
        insertException(M_SUCCESS, 6, CcConstants.EXC_DAMAGE, true, 10); // 10 天后关闭 -> 超期不计
        insertException(M_SUCCESS, 7, CcConstants.EXC_DELAY, false, 0);  // 未关闭
        insertReturn(M_SUCCESS, 10, 20);
        insertReturn(M_SUCCESS, 12, 5);
    }

    private long insertShippedBatch(YearMonth month, int day, int qty) {
        long id = ++seq + 1000;
        TransportBatch b = new TransportBatch();
        b.setBatchNo("V16-S-" + (uniq % 100000) + "-" + id);
        b.setOrderId(900000 + id);
        b.setVehicleId(900000 + id);
        b.setDrugId(900000 + id);
        b.setDrugVersionNo(1);
        b.setApprovalNoSnap("国药准字V16-" + id);
        b.setTempMinSnap(new BigDecimal("2.00"));
        b.setTempMaxSnap(new BigDecimal("8.00"));
        b.setPackageSpecSnap("1支/盒");
        b.setReceiverOrg("V16验收医院");
        b.setShipQty(qty);
        b.setHasTempExcursion(false);
        b.setStatus(CcConstants.BATCH_SHIPPED);
        b.setShipTime(month.atDay(Math.min(day, 27)).atTime(9, 30));
        batchMapper.insert(b);
        return b.getId();
    }

    private long anyBatch(YearMonth month, int day) {
        List<TransportBatch> bs = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .isNotNull("ship_time")
                .ge("ship_time", month.atDay(1).atStartOfDay())
                .lt("ship_time", month.plusMonths(1).atDay(1).atStartOfDay())
                .orderByAsc("id"));
        return bs.isEmpty() ? insertShippedBatch(month, day, 1) : bs.get(0).getId();
    }

    private void insertAlert(YearMonth month, int day, long batchId, long minutes) {
        TempAlert a = new TempAlert();
        a.setBatchId(batchId);
        a.setZoneCode(CcConstants.ZONE_REFRIGERATE);
        a.setZoneName(CcConstants.ZONE_REFRIGERATE_NAME);
        a.setTempMinSnap(new BigDecimal("2.00"));
        a.setTempMaxSnap(new BigDecimal("8.00"));
        a.setTolerateMinutes(30);
        a.setJitterGapMinutes(10);
        LocalDateTime start = month.atDay(Math.min(day, 27)).atTime(11, 0);
        a.setStartTime(start);
        a.setTriggerTime(start.plusMinutes(minutes));
        a.setLastExceedTime(start.plusMinutes(minutes));
        a.setDurationMinutes(minutes);
        a.setDescription("V16验收预警 " + minutes + " 分钟");
        a.setStatus(CcConstants.ALERT_OPEN);
        alertMapper.insert(a);
    }

    /** 温度超限异常：unbacked=true 时 alertId=null（无时长背书，触发待补） */
    private void insertTempException(YearMonth month, int day, boolean closed, Long alertId) {
        ExceptionEvent e = baseException(month, day, CcConstants.EXC_TEMP);
        e.setAlertId(alertId);
        if (closed) {
            e.setStatus(CcConstants.EXC_RESOLVED);
            e.setResolveTime(e.getOccurredTime().plusDays(1));
        }
        exceptionMapper.insert(e);
    }

    private void insertException(YearMonth month, int day, String type, boolean closed, int resolveDaysLater) {
        ExceptionEvent e = baseException(month, day, type);
        e.setAlertId(-1L); // 非温度异常，占位避免被当作无背书温度异常（温度判定只看 TEMP_EXCURSION 类型）
        if (closed) {
            e.setStatus(CcConstants.EXC_RESOLVED);
            e.setResolveTime(e.getOccurredTime().plusDays(resolveDaysLater));
        }
        exceptionMapper.insert(e);
    }

    private ExceptionEvent baseException(YearMonth month, int day, String type) {
        ExceptionEvent e = new ExceptionEvent();
        e.setBatchId(900000L + (++seq));
        e.setExceptionType(type);
        e.setDescription("V16验收异常 " + type);
        e.setOccurredTime(month.atDay(Math.min(day, 27)).atTime(10, 0));
        e.setStatus(CcConstants.EXC_OPEN);
        return e;
    }

    private void insertReturn(YearMonth month, int day, int qty) {
        ReturnBatch r = new ReturnBatch();
        r.setReturnNo("THV16" + (uniq % 100000) + "-" + (++seq));
        r.setSourceBatchId(900000L + seq);
        r.setSourceBatchNo("V16-SRC-" + seq);
        r.setReasonType(CcConstants.RETURN_REASON_DAMAGE);
        r.setReasonDetail("V16验收退货");
        r.setReturnQty(qty);
        r.setStatus(CcConstants.RETURN_REGISTERED);
        r.setCreateTime(month.atDay(Math.min(day, 27)).atTime(14, 0));
        returnBatchMapper.insert(r);
    }

    private void assertNotSameId(long a, long b) {
        assertTrue(a != b, "新版本必须是新行，不得覆盖旧版本");
    }

    /** 通过真实 Quartz 调度器立即触发月度 Job（JobFactory 完成 Spring 注入） */
    private void runQuartzMonthlyJob(String month) {
        try {
            org.quartz.JobKey key = org.quartz.JobKey.jobKey(
                    "reg-it-" + month.replace("-", ""), "coldchain-it");
            org.quartz.JobDetail detail = org.quartz.JobBuilder.newJob(
                            com.evops.coldchain.job.RegReportMonthlyJob.class)
                    .withIdentity(key)
                    .usingJobData(com.evops.coldchain.job.RegReportMonthlyJob.JOB_DATA_MONTH, month)
                    .storeDurably().build();
            quartzScheduler.addJob(detail, true);
            quartzScheduler.triggerJob(key, detail.getJobDataMap());
        } catch (Exception e) {
            throw new AssertionError("触发 Quartz 月度 Job 失败", e);
        }
    }

    /** 轮询等待 Quartz 异步 Job 为指定月生成报表（最多 5 秒） */
    private RegReport awaitReport(String month) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            RegReport r = generateService.latestVersion(month);
            if (r != null) {
                return r;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待 Quartz Job 生成 " + month + " 报表超时");
    }

    private JsonNode orNull(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    // ==================== HTTP 辅助 ====================

    private static Map<String, Object> monthBody(YearMonth m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statMonth", m.toString());
        return body;
    }

    private static Map<String, Object> caliberBody(String name, int closeWindowDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionName", name);
        body.put("closeWindowDays", closeWindowDays);
        body.put("changeReason", "V16验收");
        return body;
    }

    private JsonNode post(String user, String pw, String path, Object body) {
        HttpResp resp = raw(user, pw, HttpMethod.POST, BASE + path, body);
        assertEquals(200, resp.status, "期望 200 业务响应，实际 " + resp.status + "：" + resp.body);
        return readTree(resp.body);
    }

    private JsonNode get(String user, String pw, String path) {
        HttpResp resp = raw(user, pw, HttpMethod.GET, BASE + path, null);
        assertEquals(200, resp.status, "GET " + path + " -> " + resp.status + " " + resp.body);
        return readTree(resp.body).path("data");
    }

    private ExportHttp exportCsv(String user, String pw, Long reportId) {
        try {
            ResponseEntity<byte[]> resp = http.withBasicAuth(user, pw)
                    .getForEntity("http://localhost:" + port + BASE + "/" + reportId + "/export", byte[].class);
            String body = new String(resp.getBody(), StandardCharsets.UTF_8);
            if (body.startsWith("\uFEFF")) {
                body = body.substring(1);
            }
            ExportHttp x = new ExportHttp();
            x.status = resp.getStatusCodeValue();
            x.body = body;
            x.disposition = resp.getHeaders().getFirst("Content-Disposition");
            return x;
        } catch (HttpClientErrorException ex) {
            ExportHttp x = new ExportHttp();
            x.status = ex.getRawStatusCode();
            x.body = ex.getResponseBodyAsString();
            return x;
        }
    }

    private HttpResp raw(String user, String pw, HttpMethod method, String path, Object body) {
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<Object> entity = new org.springframework.http.HttpEntity<>(body, headers);
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
        HttpResp(int status, String body) { this.status = status; this.body = body; }
    }

    private static class ExportHttp {
        int status;
        String body;
        String disposition;
    }

    // ==================== 测试替身：可控计算失败 + 可控告警投递 ====================

    @TestConfiguration
    static class FaultyConfig {
        @Bean @Primary
        public FaultyCompute faultyRegCompute() {
            return new FaultyCompute();
        }

        @Bean @Primary
        public ScriptedRegTransport scriptedRegTransport() {
            return new ScriptedRegTransport();
        }
    }

    static class FaultyCompute extends RegReportComputeService {
        int failuresRemaining = 0;

        @Override
        public MetricBundle compute(LocalDateTime start, LocalDateTime end, int closeWindowDays) {
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new RuntimeException("模拟计算阶段失败：数据源不可用");
            }
            return super.compute(start, end, closeWindowDays);
        }
    }

    static class ScriptedRegTransport implements ExceptionNotifyTransport {
        int failuresRemaining = 0;
        final List<String> targets = new ArrayList<>();

        @Override
        public synchronized SendResult send(String endpoint, String payload) {
            targets.add(endpoint);
            if (failuresRemaining > 0) {
                failuresRemaining--;
                return SendResult.fail("模拟投递失败：监管告警对端未响应");
            }
            return SendResult.ok();
        }
    }
}
