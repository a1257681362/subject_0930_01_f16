package com.evops.coldchain;

import com.evops.coldchain.dto.EsignPhotoItem;
import com.evops.coldchain.dto.EsignUploadRequest;
import com.evops.coldchain.dto.EvidenceAppendRequest;
import com.evops.coldchain.dto.ExceptionMarkRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.TempReportRequest;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.ImproveNotifyLog;
import com.evops.coldchain.entity.ImproveTask;
import com.evops.coldchain.entity.ScoreAppeal;
import com.evops.coldchain.entity.ScoreEvidence;
import com.evops.coldchain.entity.ScorePeriod;
import com.evops.coldchain.entity.ScoreReview;
import com.evops.coldchain.entity.ScoreRule;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.entity.SignReceipt;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.ImproveNotifyLogMapper;
import com.evops.coldchain.mapper.ImproveRemindDedupMapper;
import com.evops.coldchain.mapper.ImproveTaskMapper;
import com.evops.coldchain.mapper.ScoreAppealMapper;
import com.evops.coldchain.mapper.ScoreEvidenceMapper;
import com.evops.coldchain.mapper.ScorePeriodMapper;
import com.evops.coldchain.mapper.ScoreReviewMapper;
import com.evops.coldchain.mapper.ScoreRuleMapper;
import com.evops.coldchain.mapper.ScoreSnapshotMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.coldchain.dto.ScoreAppealReviewRequest;
import com.evops.coldchain.service.ExceptionLifecycleService;
import com.evops.coldchain.service.ExceptionNotifyTransport;
import com.evops.coldchain.service.EsignService;
import com.evops.coldchain.service.ScoreAppealService;
import com.evops.coldchain.service.ScoreRuleService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.support.TestSubjects;
import com.evops.common.BusinessException;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输 V15 验收：承运商服务评分。
 *
 * 用例严格按顺序串成一条业务线：
 * 1) 评分规则版本化：周期开立绑定当前生效版本，规则调整只影响此后开立的周期；
 * 2) 关闭周期生成不可变快照：准时率/温控合格率/签收完整度/异常响应四指标分别取证
 *    （PASS/FAIL/UNEVALUABLE，无法取证不进分母），加权总分按周期绑定权重计算；
 * 3) 快照不可变 + 承运商只能查看自己的指标与申诉入口；
 * 4) 申诉期间原分数不回写；复核结论关联证据并产生改进任务；窗口外/重复申诉拒绝；
 * 5) 任务逾期自动提醒：外部依赖失败不假装成功（任务保持未提醒），恢复后恰好一条
 *    成功通知不重复；手动重试留痕；任务完成只进不退；
 * 6) Shiro 权限矩阵（401/403/200）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CarrierScoreIntegrationTest {

    private static final String BASE = "/api/cold-chain/scores";
    private static final ObjectMapper OM = new ObjectMapper();

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;

    @Autowired private TransportBatchService batchService;
    @Autowired private ExceptionLifecycleService lifecycle;
    @Autowired private EsignService esignService;
    @Autowired private ScoreRuleService ruleService;
    @Autowired private ScoreAppealService appealService;
    @Autowired private TestSubjects testSubjects;

    @Autowired private TransportBatchMapper batchMapper;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private ScoreRuleMapper ruleMapper;
    @Autowired private ScorePeriodMapper periodMapper;
    @Autowired private ScoreSnapshotMapper snapshotMapper;
    @Autowired private ScoreEvidenceMapper evidenceMapper;
    @Autowired private ScoreAppealMapper appealMapper;
    @Autowired private ScoreReviewMapper reviewMapper;
    @Autowired private ImproveTaskMapper taskMapper;
    @Autowired private ImproveNotifyLogMapper notifyMapper;
    @Autowired private ImproveRemindDedupMapper dedupMapper;

    @Autowired
    private ScriptedTransport transport;

    // 跨用例共享状态
    private static Long period1Id;
    private static Long period2Id;
    private static Long snap1Id;          // carrier1 快照
    private static Long snap2Id;          // carrier2 快照
    private static Long appealId;
    private static Long task1Id;
    private static Long task2Id;
    private static ScoreSnapshot snap1BeforeAppeal; // 申诉前快照原值（验证不回写）

    // ========== 1) 规则版本化：调整只影响下一周期 ==========

    @Test
    @Order(1)
    void ruleVersioning_periodBindsCurrentEffective() {
        ScoreRule v1 = ruleService.requireEffective();
        assertEquals(1, v1.getVersionNo());
        assertEquals(CcConstants.EFFECTIVE, v1.getStatus());
        assertEquals(30, v1.getOnTimeWeight());
        assertEquals(30, v1.getTempControlWeight());
        assertEquals(20, v1.getSignCompleteWeight());
        assertEquals(20, v1.getExceptionRespWeight());

        // 调度无规则维护权限 → 403
        HttpResp denied = raw("dispatcher", "dispatcher123", HttpMethod.POST, BASE + "/rules/draft",
                ruleBody("越权版", 25, 25, 25, 25));
        assertEquals(403, denied.status);

        // 开立周期 P1：绑定当前生效 V1
        JsonNode p1 = post("admin", "admin123", "/periods",
                periodBody("2026-10月第一周期", LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1)));
        assertTrue(p1.path("success").asBoolean(), p1.toString());
        period1Id = p1.path("data").path("id").asLong();
        assertEquals(1, p1.path("data").path("ruleVersionNo").asInt());
        assertEquals("OPEN", p1.path("data").path("status").asText());

        // 发布规则 V2（权重均衡版）：V1 被热替换
        JsonNode draft = post("admin", "admin123", "/rules/draft",
                ruleBody("权重均衡版", 25, 25, 25, 25));
        assertTrue(draft.path("success").asBoolean(), draft.toString());
        long v2Id = draft.path("data").path("id").asLong();
        JsonNode published = post("admin", "admin123", "/rules/" + v2Id + "/publish", null);
        assertTrue(published.path("success").asBoolean(), published.toString());
        assertEquals(CcConstants.SUPERSEDED, ruleMapper.selectById(v1.getId()).getStatus());

        // 权重合计非 100 拒绝
        JsonNode badWeight = post("admin", "admin123", "/rules/draft",
                ruleBody("非法权重版", 30, 30, 30, 30));
        assertFalse(badWeight.path("success").asBoolean());
        assertTrue(badWeight.path("message").asText().contains("100"));

        // 开立周期 P2：绑定 V2 —— 规则调整只影响此后开立的周期
        JsonNode p2 = post("admin", "admin123", "/periods",
                periodBody("2026-10月第二周期", LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(9)));
        assertTrue(p2.path("success").asBoolean(), p2.toString());
        period2Id = p2.path("data").path("id").asLong();
        assertEquals(2, p2.path("data").path("ruleVersionNo").asInt());

        // P1 仍绑定 V1（在评周期不受新版本影响）
        ScorePeriod p1Row = periodMapper.selectById(period1Id);
        assertEquals(1, p1Row.getRuleVersionNo());
        assertTrue(p1Row.getRuleSnapshot().contains("\"onTimeWeight\":30"));
    }

    // ========== 2) 关闭周期生成快照：四指标分别取证 ==========

    @Test
    @Order(2)
    void closePeriod_generatesImmutableSnapshotsWithEvidence() {
        LocalDateTime now = LocalDateTime.now();

        // —— 批次A（承运商1）：准时 + 电子回执齐全 + 无温度异常 ——
        long batchA = shipMinimalBatch("V15A", 1L);
        setCommitted(batchA, now.plusHours(2));
        uploadCompleteReceipt(batchA, "V15A");

        // —— 批次B（承运商1）：晚点 + 温度超限 + 响应超时延误 + 无电子回执 ——
        long batchB = shipMinimalBatch("V15B", 1L);
        setCommitted(batchB, now.minusHours(2));
        // 温度超限（自动确认即响应及时：临界=确认同刻）
        reportTemp(batchB, now.minusHours(5), new BigDecimal("11.50"));
        ExceptionEvent tempExc = batchService.listExceptions(batchB).stream()
                .filter(e -> CcConstants.EXC_TEMP.equals(e.getExceptionType()))
                .findFirst().orElseThrow(AssertionError::new);
        // 延误：3 小时前发现、现在才确认（DELAY 响应时限 120 分钟 -> 响应超时）
        ExceptionMarkRequest mark = new ExceptionMarkRequest();
        mark.setBatchId(batchB);
        mark.setExceptionType(CcConstants.EXC_DELAY);
        mark.setDescription("V15B：高速管制延误，响应超时");
        mark.setOccurredTime(now.minusHours(3));
        ExceptionEvent delay = batchService.markException(mark);
        lifecycle.confirm(delay.getId());
        closeException(tempExc.getId());
        closeException(delay.getId());
        plainSign(batchB, "V15B");

        // —— 批次C（承运商2）：全部优秀 + 响应及时的延误 ——
        long batchC = shipMinimalBatch("V15C", 2L);
        setCommitted(batchC, now.plusHours(2));
        ExceptionMarkRequest markC = new ExceptionMarkRequest();
        markC.setBatchId(batchC);
        markC.setExceptionType(CcConstants.EXC_DELAY);
        markC.setDescription("V15C：短暂拥堵，30分钟内响应");
        markC.setOccurredTime(now.minusMinutes(30));
        ExceptionEvent delayC = batchService.markException(markC);
        lifecycle.confirm(delayC.getId());
        closeException(delayC.getId());
        uploadCompleteReceipt(batchC, "V15C");

        // —— 批次D（承运商1）：无承诺时间（准时率无法取证）+ 无电子回执 ——
        long batchD = shipMinimalBatch("V15D", 1L);
        plainSign(batchD, "V15D");

        // 关闭周期 P1 -> 生成两家承运商快照
        JsonNode closed = post("admin", "admin123", "/periods/" + period1Id + "/close", null);
        assertTrue(closed.path("success").asBoolean(), closed.toString());
        assertEquals("CLOSED", closed.path("data").path("status").asText());
        assertEquals(2, closed.path("data").path("snapshotCount").asInt());

        // 重复关闭拒绝（快照只生成一次）
        JsonNode reclose = post("admin", "admin123", "/periods/" + period1Id + "/close", null);
        assertFalse(reclose.path("success").asBoolean());
        assertTrue(reclose.path("message").asText().contains("不可重复关闭"));

        // —— 承运商1 快照：逐项指标与总分 ——
        ScoreSnapshot s1 = snapshotMapper.selectOne(new QueryWrapper<ScoreSnapshot>()
                .eq("period_id", period1Id).eq("carrier_id", 1L));
        assertNotNull(s1);
        snap1Id = s1.getId();
        assertEquals(1, s1.getRuleVersionNo());
        assertEquals("CY100000001", s1.getCarrierNoSnap());
        // 准时率：A PASS、B FAIL、D 无法取证 -> 1/(1+1)=50.00
        assertRate(s1.getOnTimeRate(), "50.00");
        assertEquals(1, s1.getOnTimePass());
        assertEquals(1, s1.getOnTimeFail());
        assertEquals(1, s1.getOnTimeUnevaluable());
        // 温控合格率：A/D PASS、B FAIL -> 66.67
        assertRate(s1.getTempControlRate(), "66.67");
        assertEquals(2, s1.getTempControlPass());
        assertEquals(1, s1.getTempControlFail());
        // 签收完整度：A PASS、B/D FAIL -> 33.33
        assertRate(s1.getSignCompleteRate(), "33.33");
        assertEquals(1, s1.getSignCompletePass());
        assertEquals(2, s1.getSignCompleteFail());
        // 异常响应：温度自动确认 PASS、延误超时 FAIL -> 50.00
        assertRate(s1.getExceptionRespRate(), "50.00");
        assertEquals(1, s1.getExceptionRespPass());
        assertEquals(1, s1.getExceptionRespFail());
        // 加权总分（V1 权重 30/30/20/20）：(50*30+66.67*30+33.33*20+50*20)/100 = 51.67
        assertRate(s1.getTotalScore(), "51.67");
        assertEquals(4, s1.getScoredMetricCount());

        // —— 承运商2 快照：全部满分 ——
        ScoreSnapshot s2 = snapshotMapper.selectOne(new QueryWrapper<ScoreSnapshot>()
                .eq("period_id", period1Id).eq("carrier_id", 2L));
        assertNotNull(s2);
        snap2Id = s2.getId();
        assertRate(s2.getOnTimeRate(), "100.00");
        assertRate(s2.getTempControlRate(), "100.00");
        assertRate(s2.getSignCompleteRate(), "100.00");
        assertRate(s2.getExceptionRespRate(), "100.00");
        assertRate(s2.getTotalScore(), "100.00");

        // —— 取证明细：四指标分别落行、依据可追 ——
        List<ScoreEvidence> ev1 = evidenceMapper.selectList(new QueryWrapper<ScoreEvidence>()
                .eq("snapshot_id", snap1Id).orderByAsc("id"));
        assertEquals(3, countMetric(ev1, CcConstants.METRIC_ON_TIME));
        assertEquals(3, countMetric(ev1, CcConstants.METRIC_TEMP_CONTROL));
        assertEquals(3, countMetric(ev1, CcConstants.METRIC_SIGN_COMPLETE));
        assertEquals(2, countMetric(ev1, CcConstants.METRIC_EXCEPTION_RESPONSE));
        ScoreEvidence onTimeUneval = findEvidence(ev1, CcConstants.METRIC_ON_TIME, CcConstants.EVIDENCE_UNEVALUABLE);
        assertTrue(onTimeUneval.getBasis().contains("无承诺到达时间"), onTimeUneval.getBasis());
        ScoreEvidence tempFail = findEvidence(ev1, CcConstants.METRIC_TEMP_CONTROL, CcConstants.EVIDENCE_FAIL);
        assertEquals(tempExc.getId(), tempFail.getExceptionId());
        assertTrue(tempFail.getBasis().contains("温度超限"));
        ScoreEvidence respFail = findEvidence(ev1, CcConstants.METRIC_EXCEPTION_RESPONSE, CcConstants.EVIDENCE_FAIL);
        assertEquals(delay.getId(), respFail.getExceptionId());
        assertTrue(respFail.getBasis().contains("响应超时"), respFail.getBasis());
        ScoreEvidence signFail = findEvidence(ev1, CcConstants.METRIC_SIGN_COMPLETE, CcConstants.EVIDENCE_FAIL);
        assertTrue(signFail.getBasis().contains("无电子签收回执"));
    }

    // ========== 3) 快照不可变 + 承运商只能看自己 ==========

    @Test
    @Order(3)
    void snapshotImmutable_carrierSeesOnlyOwn() {
        snap1BeforeAppeal = snapshotMapper.selectById(snap1Id);

        // 承运商A：只见自己的快照，申诉入口开放
        JsonNode my = get("carrier", "carrier123", "/my");
        assertEquals(1, my.size());
        JsonNode mySnap = my.get(0);
        assertEquals(snap1Id, mySnap.path("snapshot").path("id").asLong());
        assertEquals(1, mySnap.path("snapshot").path("carrierId").asInt());
        assertTrue(mySnap.path("appealWindow").path("open").asBoolean());
        assertEquals(7, mySnap.path("appealWindow").path("windowDays").asInt());

        // 承运商B：只见自己（隔离对称）
        JsonNode my2 = get("carrier2", "carrier123", "/my");
        assertEquals(1, my2.size());
        assertEquals(snap2Id, my2.get(0).path("snapshot").path("id").asLong());

        // 承运商A 访问承运商B 的快照详情 -> 业务拒绝
        JsonNode cross = post("carrier", "carrier123", "/my/" + snap2Id, null, HttpMethod.GET);
        assertFalse(cross.path("success").asBoolean());
        assertTrue(cross.path("message").asText().contains("自己的"));

        // 承运商无管理岗全量台账权限 -> 403
        HttpResp forbidden = raw("carrier", "carrier123", HttpMethod.GET, BASE + "/snapshots", null);
        assertEquals(403, forbidden.status);

        // 管理岗全量视图
        JsonNode all = get("dispatcher", "dispatcher123", "/snapshots");
        assertEquals(2, all.size());
    }

    // ========== 4) 申诉不回写 + 复核关联证据 + 改进任务 ==========

    @Test
    @Order(4)
    void appeal_noRewrite_reviewWithEvidence_improveTasks() {
        // 承运商A 对准时率提交申诉
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshotId", snap1Id);
        body.put("metricType", "ON_TIME");
        body.put("reason", "高速管制属不可抗力，已提前报备，申请准时率复核");
        body.put("evidenceRefs", "管制通告#A2026-1001");
        JsonNode submitted = post("carrier", "carrier123", "/appeals/submit", body);
        assertTrue(submitted.path("success").asBoolean(), submitted.toString());
        appealId = submitted.path("data").path("id").asLong();
        assertEquals("PENDING", submitted.path("data").path("status").asText());

        // 同一指标重复待复核申诉拒绝
        JsonNode dup = post("carrier", "carrier123", "/appeals/submit", body);
        assertFalse(dup.path("success").asBoolean());
        assertTrue(dup.path("message").asText().contains("待复核"));

        // 承运商B 申诉承运商A 的快照 -> 拒绝（只能申诉自己的）
        Map<String, Object> crossBody = new LinkedHashMap<>(body);
        crossBody.put("metricType", "TEMP_CONTROL");
        JsonNode cross = post("carrier2", "carrier123", "/appeals/submit", crossBody);
        assertFalse(cross.path("success").asBoolean());
        assertTrue(cross.path("message").asText().contains("自己的"));

        // 申诉期间原分数不回写：快照行任何字段与申诉前一致
        ScoreSnapshot during = snapshotMapper.selectById(snap1Id);
        assertSnapshotEquals(snap1BeforeAppeal, during);

        // 承运商尝试复核 -> 403（无复核权限）
        HttpResp carrierReview = raw("carrier", "carrier123", HttpMethod.POST,
                BASE + "/appeals/" + appealId + "/review", reviewBody("UPHELD", "同意", "证据#1"));
        assertEquals(403, carrierReview.status);

        // 申诉人自复核（服务层职责分离兜底）-> 拒绝
        ScoreAppealReviewRequest selfReview = new ScoreAppealReviewRequest();
        selfReview.setConclusion("UPHELD");
        selfReview.setReviewNote("自审");
        selfReview.setEvidenceRefs("证据#1");
        selfReview.setTasks(Collections.singletonList(taskItem("整改", "整改内容", "冷经理", null, null)));
        assertThrows(BusinessException.class,
                () -> testSubjects.apply(3L, "carrier", () -> appealService.review(appealId, selfReview)));

        // 复核结论必须关联证据（缺证据拒绝）
        Map<String, Object> noEvidence = reviewBody("UPHELD", "同意", null);
        noEvidence.put("tasks", Collections.singletonList(taskItem("整改", "整改内容", "冷经理", null, null)));
        JsonNode rejected = post("qa", "qa123", "/appeals/" + appealId + "/review", noEvidence);
        assertFalse(rejected.path("success").asBoolean());

        // QA 复核：成立 + 关联证据 + 纠正值 + 两条改进任务
        Map<String, Object> review = reviewBody("UPHELD",
                "管制通告与报备记录齐备，准时率纠正为 100", "管制通告#A2026-1001、气象记录#W77");
        review.put("correctedRate", new BigDecimal("100.00"));
        review.put("correctedTotalScore", new BigDecimal("66.67"));
        review.put("tasks", Arrays.asList(
                taskItem("补充延误报备流程", "建立高速管制提前报备SOP", "冷经理", "carrier", null),
                taskItem("回执要素培训", "司机电子签收回执要素培训", "王调度", null, 1)));
        JsonNode reviewed = post("qa", "qa123", "/appeals/" + appealId + "/review", review);
        assertTrue(reviewed.path("success").asBoolean(), reviewed.toString());

        // 申诉状态推进 + 复核结论落行（只追加、关联证据、纠正值只落复核行）
        ScoreAppeal appeal = appealMapper.selectById(appealId);
        assertEquals("UPHELD", appeal.getStatus());
        List<ScoreReview> reviews = reviewMapper.selectList(new QueryWrapper<ScoreReview>()
                .eq("appeal_id", appealId));
        assertEquals(1, reviews.size());
        ScoreReview rv = reviews.get(0);
        assertEquals("qa", rv.getReviewByName());
        assertTrue(rv.getEvidenceRefs().contains("管制通告"));
        assertRate(rv.getCorrectedRate(), "100.00");
        assertRate(rv.getCorrectedTotalScore(), "66.67");

        // 原快照仍不回写（复核后亦然）
        ScoreSnapshot afterReview = snapshotMapper.selectById(snap1Id);
        assertSnapshotEquals(snap1BeforeAppeal, afterReview);

        // 改进任务两条：默认 3 天 / 指定 1 天
        List<ImproveTask> tasks = taskMapper.selectList(new QueryWrapper<ImproveTask>()
                .eq("appeal_id", appealId).orderByAsc("id"));
        assertEquals(2, tasks.size());
        task1Id = tasks.get(0).getId();
        task2Id = tasks.get(1).getId();
        assertEquals("PENDING", tasks.get(0).getStatus());
        assertEquals("NONE", tasks.get(0).getRemindStatus());
        assertEquals("冷经理", tasks.get(0).getOwnerName());
        assertTrue(tasks.get(0).getDueTime().isAfter(LocalDateTime.now().plusDays(2)));
        assertTrue(tasks.get(1).getDueTime().isBefore(LocalDateTime.now().plusDays(2)));

        // 重复复核拒绝
        JsonNode reReview = post("qa", "qa123", "/appeals/" + appealId + "/review", review);
        assertFalse(reReview.path("success").asBoolean());

        // 详情视图：原值与复核口径并列（快照 50.00 原值 + 复核纠正 100.00）
        JsonNode detail = get("dispatcher", "dispatcher123", "/snapshots/" + snap1Id);
        assertEquals(50.00, detail.path("snapshot").path("onTimeRate").asDouble(), 0.001);
        JsonNode reviewNode = detail.path("appeals").get(0).path("reviews").get(0);
        assertEquals(100.00, reviewNode.path("correctedRate").asDouble(), 0.001);
        assertEquals("UPHELD", reviewNode.path("conclusion").asText());

        // 申诉窗口外拒绝：快照生成时间回拨 10 天（窗口 7 天已过）
        ScoreSnapshot expired = snapshotMapper.selectById(snap1Id);
        expired.setGenerateTime(LocalDateTime.now().minusDays(10));
        snapshotMapper.updateById(expired);
        Map<String, Object> lateBody = new LinkedHashMap<>(body);
        lateBody.put("metricType", "SIGN_COMPLETE");
        JsonNode late = post("carrier", "carrier123", "/appeals/submit", lateBody);
        assertFalse(late.path("success").asBoolean());
        assertTrue(late.path("message").asText().contains("申诉窗口已关闭"));
    }

    // ========== 5) 逾期提醒：失败不假装成功、恢复不重复 ==========

    @Test
    @Order(5)
    void overdueRemind_failureNotPretended_recoveryNoDuplicate() {
        // 规则配置 webhook 投递地址（模拟外部依赖）；任务1 逾期
        ScoreRule v1 = ruleMapper.selectList(new QueryWrapper<ScoreRule>()
                .eq("version_no", 1)).get(0);
        v1.setNotifyEndpoint("http://notify.local/hook");
        ruleMapper.updateById(v1);
        ImproveTask task1 = taskMapper.selectById(task1Id);
        task1.setDueTime(LocalDateTime.now().minusHours(1));
        taskMapper.updateById(task1);

        // 外部依赖无响应：第一次扫描投递失败
        transport.failuresRemaining = 1;
        JsonNode scan1 = post("admin", "admin123", "/tasks/remind-scan", null);
        assertTrue(scan1.path("success").asBoolean(), scan1.toString());
        assertEquals(0, scan1.path("data").path("sent").asInt());
        assertEquals(1, scan1.path("data").path("failed").asInt());

        // 不假装成功：任务保持未提醒，失败流水留痕（渠道/原因/尝试次序）
        ImproveTask after1 = taskMapper.selectById(task1Id);
        assertEquals("NONE", after1.getRemindStatus());
        assertNull(after1.getRemindTime());
        List<ImproveNotifyLog> logs1 = notifyMapper.selectList(new QueryWrapper<ImproveNotifyLog>()
                .eq("task_id", task1Id).orderByAsc("id"));
        assertEquals(1, logs1.size());
        assertEquals("FAILED", logs1.get(0).getSendStatus());
        assertEquals("WEBHOOK", logs1.get(0).getChannel());
        assertEquals(1, logs1.get(0).getAttemptNo().intValue());
        assertTrue(logs1.get(0).getFailReason().contains("模拟投递失败"));
        assertEquals(0, dedupMapper.selectCount(new QueryWrapper<com.evops.coldchain.entity.ImproveRemindDedup>()
                .eq("task_id", task1Id)).intValue());

        // 依赖恢复：第二次扫描恰好一条成功通知
        JsonNode scan2 = post("admin", "admin123", "/tasks/remind-scan", null);
        assertEquals(1, scan2.path("data").path("sent").asInt());
        ImproveTask after2 = taskMapper.selectById(task1Id);
        assertEquals("SENT", after2.getRemindStatus());
        assertNotNull(after2.getRemindTime());
        List<ImproveNotifyLog> logs2 = notifyMapper.selectList(new QueryWrapper<ImproveNotifyLog>()
                .eq("task_id", task1Id).orderByAsc("id"));
        assertEquals(2, logs2.size());
        assertEquals("SUCCESS", logs2.get(1).getSendStatus());
        assertEquals(2, logs2.get(1).getAttemptNo().intValue());
        assertEquals(1, dedupMapper.selectCount(new QueryWrapper<com.evops.coldchain.entity.ImproveRemindDedup>()
                .eq("task_id", task1Id)).intValue());

        // 恢复后再扫描：不重复通知（零新增流水）
        JsonNode scan3 = post("admin", "admin123", "/tasks/remind-scan", null);
        assertEquals(0, scan3.path("data").path("sent").asInt());
        assertEquals(2, notifyMapper.selectCount(new QueryWrapper<ImproveNotifyLog>()
                .eq("task_id", task1Id)).intValue());

        // 已成功送达后手动重试拒绝
        Map<String, Object> retry = new LinkedHashMap<>();
        retry.put("retryReason", "人工确认送达");
        JsonNode retryResp = post("admin", "admin123", "/tasks/" + task1Id + "/remind-retry", retry);
        assertFalse(retryResp.path("success").asBoolean());
        assertTrue(retryResp.path("message").asText().contains("无需重试"));

        // 任务完成只进不退
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("doneNote", "报备SOP已发布并培训到位");
        JsonNode doneResp = post("dispatcher", "dispatcher123", "/tasks/" + task2Id + "/done", done);
        assertTrue(doneResp.path("success").asBoolean(), doneResp.toString());
        assertEquals("DONE", doneResp.path("data").path("status").asText());
        JsonNode reDone = post("dispatcher", "dispatcher123", "/tasks/" + task2Id + "/done", done);
        assertFalse(reDone.path("success").asBoolean());
    }

    // ========== 6) 权限矩阵 ==========

    @Test
    @Order(6)
    void permissions_matrix() {
        // 未认证 -> 401
        HttpResp anon = raw(null, null, HttpMethod.GET, BASE + "/snapshots", null);
        assertEquals(401, anon.status);

        // 收货岗无评分查看权限 -> 403
        assertEquals(403, raw("receiver", "receiver123", HttpMethod.GET, BASE + "/snapshots", null).status);
        // 承运岗管理动作全拒 -> 403
        assertEquals(403, raw("carrier", "carrier123", HttpMethod.POST, BASE + "/rules/draft",
                ruleBody("X", 25, 25, 25, 25)).status);
        assertEquals(403, raw("carrier", "carrier123", HttpMethod.POST, BASE + "/periods",
                periodBody("X", LocalDateTime.now(), LocalDateTime.now().plusDays(1))).status);
        assertEquals(403, raw("carrier", "carrier123", HttpMethod.POST,
                BASE + "/periods/" + period2Id + "/close", null).status);
        assertEquals(403, raw("carrier", "carrier123", HttpMethod.POST,
                BASE + "/tasks/" + task1Id + "/done", Collections.singletonMap("doneNote", "x")).status);
        assertEquals(403, raw("carrier", "carrier123", HttpMethod.POST,
                BASE + "/tasks/" + task1Id + "/remind-retry",
                Collections.singletonMap("retryReason", "x")).status);

        // 管理岗正路径 -> 200
        assertEquals(200, raw("admin", "admin123", HttpMethod.GET, BASE + "/rules", null).status);
        assertEquals(200, raw("admin", "admin123", HttpMethod.GET, BASE + "/periods", null).status);
        assertEquals(200, raw("qa", "qa123", HttpMethod.GET, BASE + "/appeals", null).status);
        assertEquals(200, raw("finance", "finance123", HttpMethod.GET, BASE + "/snapshots", null).status);
        assertEquals(200, raw("dispatcher", "dispatcher123", HttpMethod.GET, BASE + "/tasks", null).status);
        // 承运岗正路径 -> 200
        assertEquals(200, raw("carrier", "carrier123", HttpMethod.GET, BASE + "/my", null).status);
        assertEquals(200, raw("carrier2", "carrier123", HttpMethod.GET, BASE + "/my", null).status);
    }

    // ==================== 造数与断言辅助 ====================

    /** 最小造批链路（药品→发货单→车辆→探头→派车→发运），车辆归属指定承运商 */
    private long shipMinimalBatch(String tag, Long carrierId) {
        long nano = System.nanoTime();
        com.evops.coldchain.dto.DrugCreateRequest dc = new com.evops.coldchain.dto.DrugCreateRequest();
        dc.setDrugName(tag + "-药品-" + nano);
        dc.setApprovalNo("国药准字V15-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService().create(dc).getId();

        com.evops.coldchain.dto.OrderCreateRequest oc = new com.evops.coldchain.dto.OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg(tag + "医院");
        oc.setShipQty(10);
        Long orderId = orderService().create(oc).getId();

        com.evops.coldchain.dto.VehicleCreateRequest vr = new com.evops.coldchain.dto.VehicleCreateRequest();
        vr.setPlateNo("测15·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName(tag + "物流");
        vr.setDriverName("赵司机");
        Long vehicleId = vehicleService().create(vr).getId();
        Vehicle vehicle = vehicleMapper.selectById(vehicleId);
        vehicle.setCarrierId(carrierId);
        vehicleMapper.updateById(vehicle);

        com.evops.coldchain.dto.ProbeCreateRequest pc = new com.evops.coldchain.dto.ProbeCreateRequest();
        pc.setSerialNo("SN-V15-" + nano);
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V15-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V15校准证书-" + nano);
        long probeId = probeService().createProbe(pc).getId();

        com.evops.coldchain.dto.ProbeBindRequest pb = new com.evops.coldchain.dto.ProbeBindRequest();
        pb.setRequestId("v15bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService().bindIdempotent(pb);

        com.evops.coldchain.dto.DispatchRequest dr = new com.evops.coldchain.dto.DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        long batchId = batchService.dispatch(dr).getId();
        batchService.ship(batchId);
        return batchId;
    }

    /** 固化承诺到达时间（模拟 V10 时窗解析结果，准时率取证基准） */
    private void setCommitted(long batchId, LocalDateTime committed) {
        TransportBatch b = batchMapper.selectById(batchId);
        b.setCommittedArrivalTime(committed);
        batchMapper.updateById(b);
    }

    private void reportTemp(long batchId, LocalDateTime recordTime, BigDecimal temp) {
        TempReportRequest req = new TempReportRequest();
        req.setBatchId(batchId);
        req.setRecordTime(recordTime);
        req.setTemperature(temp);
        req.setRecordedBy("车载温控仪");
        batchService.reportTemperature(req);
    }

    /** 上传要素齐全的电子回执（自动激活批次签收） */
    private void uploadCompleteReceipt(long batchId, String tag) {
        TransportBatch b = batchService.requireBatch(batchId);
        EsignUploadRequest req = new EsignUploadRequest();
        req.setRequestId("v15-esign-" + tag + "-" + batchId);
        req.setBatchId(batchId);
        req.setSource("ONLINE");
        req.setSignBy(tag + "院药库");
        req.setReceivedApprovalNo(b.getApprovalNoSnap());
        req.setReceivedPackageSpec(b.getPackageSpecSnap());
        req.setConfirmQty(b.getShipQty());
        req.setDeviceTime(LocalDateTime.now());
        req.setSignatureUrl("/v15/sign/" + batchId + ".png");
        req.setLocationAddress("收货区月台");
        EsignPhotoItem photo = new EsignPhotoItem();
        photo.setPhotoName("签收照片.jpg");
        photo.setPhotoUrl("/v15/photo/" + batchId + ".jpg");
        req.setPhotos(Collections.singletonList(photo));
        esignService.upload(req);

        TransportBatch signed = batchService.requireBatch(batchId);
        assertEquals(CcConstants.BATCH_SIGNED, signed.getStatus(), tag + " 回执要素齐全应自动激活签收");
        List<SignReceipt> receipts = receiptMapper().selectList(
                new QueryWrapper<SignReceipt>().eq("batch_id", batchId));
        assertEquals(1, receipts.size());
        assertEquals(CcConstants.RECEIPT_EFFECTIVE, receipts.get(0).getStatus());
    }

    private void plainSign(long batchId, String tag) {
        TransportBatch b = batchService.requireBatch(batchId);
        SignRequest sign = new SignRequest();
        sign.setBatchId(batchId);
        sign.setSignBy(tag + "院药库");
        sign.setReceivedApprovalNo(b.getApprovalNoSnap());
        sign.setReceivedPackageSpec(b.getPackageSpecSnap());
        sign.setConfirmQty(b.getShipQty());
        batchService.sign(sign);
    }

    /** 分级异常处置闭环：追加证据 -> 引用证据的结论 -> QA 复核关闭 */
    private void closeException(Long excId) {
        EvidenceAppendRequest ev = new EvidenceAppendRequest();
        ev.setExceptionId(excId);
        ev.setFileName("proof-" + excId + ".txt");
        ev.setFileUrl("/v15/proof/" + excId);
        ev.setNote("V15验收处置证据");
        batchService.appendEvidence(ev);
        Long evidenceId = batchService.listEvidence(excId).get(0).getId();
        lifecycle.conclude(excId, "已处置并评估，申请复核关闭", Collections.singletonList(evidenceId));
        testSubjects.runAs(6L, "qa", () -> lifecycle.review(excId, true, "QA 复核通过关闭"));
    }

    private void assertRate(BigDecimal actual, String expected) {
        assertNotNull(actual, "比率不应为空");
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                "期望 " + expected + " 实际 " + actual);
    }

    private long countMetric(List<ScoreEvidence> rows, String metric) {
        return rows.stream().filter(e -> metric.equals(e.getMetricType())).count();
    }

    private ScoreEvidence findEvidence(List<ScoreEvidence> rows, String metric, String result) {
        return rows.stream()
                .filter(e -> metric.equals(e.getMetricType()) && result.equals(e.getResult()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少取证行 " + metric + "/" + result));
    }

    /** 快照不可变断言：申诉/复核前后逐字段一致 */
    private void assertSnapshotEquals(ScoreSnapshot before, ScoreSnapshot after) {
        assertEquals(before.getOnTimeRate(), after.getOnTimeRate());
        assertEquals(before.getTempControlRate(), after.getTempControlRate());
        assertEquals(before.getSignCompleteRate(), after.getSignCompleteRate());
        assertEquals(before.getExceptionRespRate(), after.getExceptionRespRate());
        assertEquals(before.getTotalScore(), after.getTotalScore());
        assertEquals(before.getOnTimePass(), after.getOnTimePass());
        assertEquals(before.getOnTimeFail(), after.getOnTimeFail());
        assertEquals(before.getGenerateTime(), after.getGenerateTime());
        assertEquals(before.getCreateTime(), after.getCreateTime());
    }

    private static Map<String, Object> ruleBody(String name, int w1, int w2, int w3, int w4) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("versionName", name);
        body.put("onTimeWeight", w1);
        body.put("tempControlWeight", w2);
        body.put("signCompleteWeight", w3);
        body.put("exceptionRespWeight", w4);
        body.put("appealWindowDays", 7);
        body.put("taskDueDays", 3);
        body.put("changeReason", "V15验收");
        return body;
    }

    private static Map<String, Object> periodBody(String name, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodName", name);
        body.put("periodStart", start.toString());
        body.put("periodEnd", end.toString());
        return body;
    }

    private static Map<String, Object> reviewBody(String conclusion, String note, String evidenceRefs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conclusion", conclusion);
        body.put("reviewNote", note);
        if (evidenceRefs != null) {
            body.put("evidenceRefs", evidenceRefs);
        }
        return body;
    }

    private static ScoreAppealReviewRequest.ImproveTaskItem taskItem(
            String title, String content, String owner, String account, Integer dueDays) {
        ScoreAppealReviewRequest.ImproveTaskItem item = new ScoreAppealReviewRequest.ImproveTaskItem();
        item.setTitle(title);
        item.setContent(content);
        item.setOwnerName(owner);
        item.setOwnerAccount(account);
        item.setDueDays(dueDays);
        return item;
    }

    // —— HTTP 辅助（与 V7 验收同风格）——

    private JsonNode post(String user, String pw, String path, Object body) {
        return post(user, pw, path, body, HttpMethod.POST);
    }

    private JsonNode post(String user, String pw, String path, Object body, HttpMethod method) {
        HttpResp resp = raw(user, pw, method, BASE + path, body);
        if (resp.status != 200) {
            throw new AssertionError("期望 200 业务响应，实际 " + resp.status + "：" + resp.body);
        }
        return readTree(resp.body);
    }

    private JsonNode get(String user, String pw, String path) {
        HttpResp resp = raw(user, pw, HttpMethod.GET, BASE + path, null);
        assertEquals(200, resp.status, "GET " + path + " -> " + resp.status + " " + resp.body);
        return readTree(resp.body).path("data");
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

    // —— 服务直连（与 V14 验收同风格；无 Shiro 上下文时回退 system 操作者）——
    @Autowired private com.evops.coldchain.service.DrugService drugService;
    @Autowired private com.evops.coldchain.service.OrderService orderService;
    @Autowired private com.evops.coldchain.service.VehicleService vehicleService;
    @Autowired private com.evops.coldchain.service.ProbeService probeService;
    @Autowired private com.evops.coldchain.mapper.SignReceiptMapper signReceiptMapper;

    private com.evops.coldchain.service.DrugService drugService() { return drugService; }
    private com.evops.coldchain.service.OrderService orderService() { return orderService; }
    private com.evops.coldchain.service.VehicleService vehicleService() { return vehicleService; }
    private com.evops.coldchain.service.ProbeService probeService() { return probeService; }
    private com.evops.coldchain.mapper.SignReceiptMapper receiptMapper() { return signReceiptMapper; }

    /** 可控通知通道替身：确定性制造外部依赖失败与恢复（与 V6 验收同风格） */
    @TestConfiguration
    static class ScriptedTransportConfig {
        @Bean
        @Primary
        public ExceptionNotifyTransport scriptedScoreTransport() {
            return new ScriptedTransport();
        }
    }

    static class ScriptedTransport implements ExceptionNotifyTransport {
        int failuresRemaining = 0;
        final List<String> endpoints = new ArrayList<>();

        @Override
        public synchronized SendResult send(String endpoint, String payload) {
            endpoints.add(endpoint);
            if (failuresRemaining > 0) {
                failuresRemaining--;
                return SendResult.fail("模拟投递失败：对端未响应");
            }
            return SendResult.ok();
        }
    }
}
