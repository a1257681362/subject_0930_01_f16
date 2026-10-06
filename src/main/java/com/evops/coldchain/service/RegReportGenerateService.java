package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.RegReport;
import com.evops.coldchain.entity.RegReportCaliber;
import com.evops.coldchain.entity.RegReportDownload;
import com.evops.coldchain.entity.RegReportError;
import com.evops.coldchain.mapper.RegReportDownloadMapper;
import com.evops.coldchain.mapper.RegReportErrorMapper;
import com.evops.coldchain.mapper.RegReportMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷链运输监管报表生成与台账（V16）。
 *
 * - 生成时绑定当前生效口径版本并固化整版 JSON 快照；按月汇总四指标（{@link RegReportComputeService}）。
 * - 一月多版本：Quartz 定时对每月只生成首版（幂等，已有版本即跳过）；人工重算只追加同月下一版本，
 *   绝不覆盖旧版本，已下载文件（文件名含版本号+报表编号）永久可复核。
 * - 缺关键数据：指标数值 NULL、列入待补，整体 PENDING_SUPPLEMENT，不静默填零（计算服务区分真零/待补）。
 * - 生成失败：持久化事务回滚（不产生报表行），错误链以独立事务落库并按口径 webhook 告警（无 endpoint 站内留痕）。
 * - 列表/详情/导出同读同一份不可变快照；导出逐次写下载留痕（行数+内容 SHA-256）。
 */
@Service
public class RegReportGenerateService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> NOTE_TYPE =
            new TypeReference<LinkedHashMap<String, String>>() {};

    @Resource
    private RegReportMapper reportMapper;
    @Resource
    private RegReportErrorMapper errorMapper;
    @Resource
    private RegReportDownloadMapper downloadMapper;
    @Resource
    private RegReportCaliberService caliberService;
    @Resource
    private RegReportComputeService computeService;
    @Resource
    private RegReportVersionPersister persister;
    @Resource
    private RegReportFailureRecorder failureRecorder;
    @Resource
    private ExceptionNotifyTransport notifyTransport;
    @Resource
    private OpLogService opLogService;

    /** 生成结果摘要（供定时任务/接口返回） */
    public static class GenerateOutcome {
        public boolean created;
        public boolean skipped;
        public RegReport report;
        public Long errorId;

        static GenerateOutcome created(RegReport r) {
            GenerateOutcome o = new GenerateOutcome();
            o.created = true;
            o.report = r;
            return o;
        }

        static GenerateOutcome skipped(RegReport existing) {
            GenerateOutcome o = new GenerateOutcome();
            o.skipped = true;
            o.report = existing;
            return o;
        }
    }

    /**
     * Quartz 定时入口：每月幂等生成首版；该月已有任意版本（含待补）则跳过，不重复、不覆盖。
     */
    public GenerateOutcome generateScheduled(YearMonth month) {
        RegReport existing = latestVersion(month.toString());
        if (existing != null) {
            return GenerateOutcome.skipped(existing);
        }
        return doGenerate(month, CcConstants.TRIGGER_SCHEDULED);
    }

    /** 人工生成/重算：无版本生成 V1，已有版本只追加下一版本（绝不覆盖旧版本/已下载文件）。 */
    public GenerateOutcome generateManual(String statMonth) {
        return doGenerate(parseMonth(statMonth), CcConstants.TRIGGER_MANUAL);
    }

    private GenerateOutcome doGenerate(YearMonth month, String triggerSource) {
        String statMonth = month.toString();
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();

        // 1) 载入生效口径（失败也要落错误链 + 告警）
        RegReportCaliber caliber;
        try {
            caliber = caliberService.requireEffective();
        } catch (RuntimeException ex) {
            return fail(statMonth, null, null, triggerSource, "LOAD_CALIBER", ex);
        }

        // 2) 计算指标（缺关键数据不抛错，由 bundle 标待补；仅技术性异常才视为生成失败）
        RegReportComputeService.MetricBundle bundle;
        try {
            bundle = computeService.compute(start, end, caliber.getCloseWindowDays());
        } catch (RuntimeException ex) {
            return fail(statMonth, caliber.getId(), caliber.getVersionNo(),
                    triggerSource, "COMPUTE", ex);
        }

        // 3) 跨 Bean 事务化持久化不可变快照新版本（抛错整事务回滚，不留半截报表行）
        try {
            return GenerateOutcome.created(persister.persist(month, start, end, caliber, triggerSource, bundle));
        } catch (RuntimeException ex) {
            return fail(statMonth, caliber.getId(), caliber.getVersionNo(),
                    triggerSource, "PERSIST", ex);
        }
    }

    /** 失败处理：独立事务落错误链 + 按口径 webhook 告警（无 endpoint 站内留痕）；随后抛业务异常 */
    private GenerateOutcome fail(String statMonth, Long caliberId, Integer caliberVersionNo,
                                 String triggerSource, String stage, RuntimeException ex) {
        String endpoint = resolveEndpoint(caliberId);

        String alertStatus;
        String alertFailReason = null;
        if (endpoint == null) {
            // 无 webhook：站内错误链留痕即告警
            alertStatus = "SITE";
        } else {
            String payload = String.format(
                    "{\"type\":\"REG_REPORT_GENERATE_FAILED\",\"statMonth\":\"%s\",\"stage\":\"%s\","
                            + "\"trigger\":\"%s\",\"caliberVersion\":%s,\"error\":\"%s\"}",
                    statMonth, stage, triggerSource,
                    caliberVersionNo == null ? "null" : caliberVersionNo.toString(),
                    escapeJson(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            ExceptionNotifyTransport.SendResult result;
            try {
                result = notifyTransport.send(endpoint, payload);
            } catch (RuntimeException transportEx) {
                result = ExceptionNotifyTransport.SendResult.fail(
                        "告警通道异常：" + transportEx.getClass().getSimpleName());
            }
            if (result.isSuccess()) {
                alertStatus = CcConstants.NOTIFY_SUCCESS;
            } else {
                alertStatus = CcConstants.NOTIFY_FAILED;
                alertFailReason = result.getFailReason();
            }
        }

        Long errorId = failureRecorder.record(statMonth, caliberId, caliberVersionNo, triggerSource,
                stage, ex, alertStatus, endpoint, alertFailReason);

        opLogService.log(CcConstants.ENT_REG_REPORT, null, null, CcConstants.ACT_REG_REPORT_FAIL,
                String.format("监管报表生成失败：统计月=%s，触发=%s，阶段=%s，错误链#%d，告警=%s%s",
                        statMonth, triggerSource, stage, errorId, alertStatus,
                        alertFailReason == null ? "" : "（告警失败原因：" + alertFailReason + "）"));

        throw new BusinessException("监管报表生成失败（阶段 " + stage + "，错误链#" + errorId
                + "，已告警并保留错误链路）：" + ex.getMessage());
    }

    private String resolveEndpoint(Long caliberId) {
        if (caliberId == null) {
            return null;
        }
        try {
            return caliberService.require(caliberId).getNotifyEndpoint();
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    // ==================== 台账查询（列表/详情/导出同读快照） ====================

    public List<RegReport> list(String statMonth) {
        QueryWrapper<RegReport> qw = new QueryWrapper<RegReport>()
                .orderByDesc("stat_month").orderByDesc("version_no").orderByDesc("id");
        if (statMonth != null && !statMonth.trim().isEmpty()) {
            qw.eq("stat_month", statMonth.trim());
        }
        return reportMapper.selectList(qw);
    }

    public RegReport require(Long id) {
        RegReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BusinessException("监管报表不存在: " + id);
        }
        return report;
    }

    public RegReport latestVersion(String statMonth) {
        List<RegReport> rows = reportMapper.selectList(new QueryWrapper<RegReport>()
                .eq("stat_month", statMonth).orderByDesc("version_no").last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 详情：报表快照 + 待补指标与原因 + 下载留痕 + 同统计月错误链（供交叉核对） */
    public Map<String, Object> detail(Long id) {
        RegReport report = require(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("report", report);
        view.put("pendingMetricList", splitPending(report.getPendingMetrics()));
        view.put("supplementReasons", parseNoteJson(report.getSupplementNote()));
        view.put("downloads", listDownloads(report.getId()));
        view.put("errors", listErrors(report.getStatMonth()));
        return view;
    }

    public List<RegReportDownload> listDownloads(Long reportId) {
        return downloadMapper.selectList(new QueryWrapper<RegReportDownload>()
                .eq("report_id", reportId).orderByAsc("id"));
    }

    public List<RegReportError> listErrors(String statMonth) {
        QueryWrapper<RegReportError> qw = new QueryWrapper<RegReportError>().orderByDesc("id");
        if (statMonth != null && !statMonth.trim().isEmpty()) {
            qw.eq("stat_month", statMonth.trim());
        }
        return errorMapper.selectList(qw);
    }

    // ==================== 导出（只读快照 + 逐次留痕） ====================

    public static class ExportResult {
        public String csv;
        public String fileName;
        public RegReportDownload trace;
    }

    /**
     * 依据不可变快照导出 CSV：同版本重复导出内容 SHA-256 一致；文件名含统计月+版本号+报表编号，
     * 重算新版本文件名不同，绝不覆盖已下载文件。
     */
    public ExportResult export(Long reportId, String channel) {
        RegReport r = require(reportId);
        String fileName = "reg_report_" + r.getStatMonth() + "_v" + r.getVersionNo()
                + "_" + r.getReportNo() + ".csv";
        String csv = buildCsv(r);

        RegReportDownload trace = new RegReportDownload();
        trace.setReportId(r.getId());
        trace.setReportNo(r.getReportNo());
        trace.setStatMonth(r.getStatMonth());
        trace.setVersionNo(r.getVersionNo());
        trace.setChannel(channel == null ? CcConstants.DOWNLOAD_CHANNEL_API : channel);
        trace.setExportRows(METRIC_ROWS);
        trace.setContentSha256(sha256(csv));
        trace.setFileName(fileName);
        trace.setOperator(UserContext.currentName());
        downloadMapper.insert(trace);

        opLogService.log(CcConstants.ENT_REG_REPORT, r.getId(), null, CcConstants.ACT_REG_REPORT_EXPORT,
                String.format("导出监管报表 %s（%s V%d，状态=%s，口径 V%d）：文件=%s，指标行=%d，SHA-256=%s，渠道=%s",
                        r.getReportNo(), r.getStatMonth(), r.getVersionNo(), r.getStatus(),
                        r.getCaliberVersionNo(), fileName, METRIC_ROWS,
                        trace.getContentSha256(), trace.getChannel()));

        ExportResult result = new ExportResult();
        result.csv = csv;
        result.fileName = fileName;
        result.trace = trace;
        return result;
    }

    /** 导出表体的指标行数（发运批次数/发运数量/超温时长/异常关闭率/退货数量） */
    private static final int METRIC_ROWS = 5;

    private String buildCsv(RegReport r) {
        Map<String, String> reasons = parseNoteJson(r.getSupplementNote());
        StringBuilder sb = new StringBuilder();
        sb.append("# 冷链运输监管报表（不可变快照）\n");
        sb.append(csvLine("报表编号", r.getReportNo()));
        sb.append(csvLine("统计月", r.getStatMonth()));
        sb.append(csvLine("版本号", "V" + r.getVersionNo()));
        sb.append(csvLine("状态", statusName(r.getStatus())));
        sb.append(csvLine("统计口径版本", "V" + r.getCaliberVersionNo()));
        sb.append(csvLine("口径快照", r.getCaliberSnapshot()));
        sb.append(csvLine("统计区间", r.getPeriodStart() + " ~ " + r.getPeriodEnd() + "（左闭右开）"));
        sb.append(csvLine("触发方式",
                CcConstants.TRIGGER_SCHEDULED.equals(r.getTriggerSource()) ? "定时生成" : "人工生成/重算"));
        sb.append(csvLine("生成时间", r.getGenerateTime()));
        sb.append(csvLine("生成人", r.getGeneratedByName()));
        sb.append('\n');
        sb.append("指标,数值,单位,数据状态,待补原因\n");
        appendMetric(sb, "发运量-批次数", r.getShippedBatchCount(), "批",
                CcConstants.REG_METRIC_SHIPMENT, r.getPendingMetrics(), reasons);
        appendMetric(sb, "发运量-发运数量", r.getShipQtyTotal(), "件",
                CcConstants.REG_METRIC_SHIPMENT, r.getPendingMetrics(), reasons);
        appendMetric(sb, "超温时长", r.getOvertempMinutes(), "分钟",
                CcConstants.REG_METRIC_OVERTEMP, r.getPendingMetrics(), reasons);
        appendMetric(sb, "异常关闭率", r.getExceptionCloseRate(),
                "%（" + nv(r.getExceptionClosedCount()) + "/" + nv(r.getExceptionTotalCount()) + " 已关闭）",
                CcConstants.REG_METRIC_CLOSE_RATE, r.getPendingMetrics(), reasons);
        appendMetric(sb, "退货数量", r.getReturnQtyTotal(), "件",
                CcConstants.REG_METRIC_RETURN, r.getPendingMetrics(), reasons);
        return sb.toString();
    }

    private void appendMetric(StringBuilder sb, String name, Object value, String unit, String metricKey,
                              String pendingMetrics, Map<String, String> reasons) {
        boolean pending = splitPending(pendingMetrics).contains(metricKey);
        String status = pending ? "待补" : "齐备";
        String number = pending ? "待补" : csv(value);
        String reason = pending ? reasons.getOrDefault(metricKey, "关键数据缺失") : "";
        sb.append(csv(name)).append(',').append(number).append(',').append(csv(unit))
                .append(',').append(status).append(',').append(csv(reason)).append('\n');
    }

    private String csvLine(String k, Object v) {
        return csv(k) + "," + csv(v) + "\n";
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String nv(Object v) {
        return v == null ? "-" : String.valueOf(v);
    }

    private String statusName(String status) {
        if (CcConstants.REPORT_SUCCESS.equals(status)) {
            return "已生成（数据齐备）";
        }
        if (CcConstants.REPORT_PENDING.equals(status)) {
            return "待补（关键数据缺失，未静默填零）";
        }
        return status;
    }

    private List<String> splitPending(String pendingMetrics) {
        List<String> keys = new ArrayList<>();
        if (pendingMetrics != null && !pendingMetrics.trim().isEmpty()) {
            for (String k : pendingMetrics.split(",")) {
                if (!k.trim().isEmpty()) {
                    keys.add(k.trim());
                }
            }
        }
        return keys;
    }

    private Map<String, String> parseNoteJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(json, NOTE_TYPE);
        } catch (Exception e) {
            // 快照备注仅用于展示，解析失败不影响报表读取
            return new LinkedHashMap<>();
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private YearMonth parseMonth(String statMonth) {
        try {
            return YearMonth.parse(statMonth);
        } catch (Exception e) {
            throw new BusinessException("统计月格式必须为 yyyy-MM：" + statMonth);
        }
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }
}
