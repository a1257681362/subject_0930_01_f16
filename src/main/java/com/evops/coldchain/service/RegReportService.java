package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.RegReport;
import com.evops.coldchain.entity.RegReportAlert;
import com.evops.coldchain.entity.RegReportCaliber;
import com.evops.coldchain.entity.RegReportRun;
import com.evops.coldchain.entity.RegReportVersion;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.mapper.RegReportMapper;
import com.evops.coldchain.mapper.RegReportRunMapper;
import com.evops.coldchain.mapper.RegReportVersionMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冷链运输监管报表版本化（V16）主编排。
 *
 * - Quartz 定时（见 {@code RegReportGenerateScheduler}）按月生成上一自然月全域报表快照；
 *   手动可对指定月/区域生成或重新计算。
 * - 每次生成都绑定当前生效统计口径版本并固化 JSON 快照；重新计算只新增版本号，
 *   绝不更新/覆盖历史版本与已下载文件。
 * - 缺关键数据：版本状态 PENDING_SUPPLEMENT、缺失指标为 NULL（不静默填零）。
 * - 生成失败：不产生快照版本；运行记录保留错误类型/message/完整 cause 链路/堆栈，
 *   并发告警（复用 V6 通知通道），失败也逐次留痕。
 */
@Service
public class RegReportService {

    private static final Logger log = LoggerFactory.getLogger(RegReportService.class);

    @Resource
    private RegReportMapper reportMapper;
    @Resource
    private RegReportVersionMapper versionMapper;
    @Resource
    private RegReportRunMapper runMapper;
    @Resource
    private OpLogMapper opLogMapper;
    @Resource
    private RegReportRepository repository;
    @Resource
    private RegReportComputeService computeService;
    @Resource
    private RegReportCaliberService caliberService;
    @Resource
    private RegReportCsvRenderer csvRenderer;
    @Resource
    private RegReportFileStore fileStore;
    @Resource
    private InspectorRegionService regionService;
    @Resource
    private ExceptionNotifyTransport notifyTransport;

    /** 同“月+区域”串行生成，避免并发产生相同版本号 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /** 手动生成/重新计算（始终新增版本）；region 空表示全域汇总（仅全域账号） */
    public RegReportVersion generateManual(String month, String region) {
        String r = normRegion(region);
        java.util.Set<String> mine = regionService.myRegions();
        if (r.isEmpty()) {
            if (!regionService.isGlobal(mine)) {
                throw new BusinessException("监管区域隔离：仅全域账号可生成全域汇总监管报表");
            }
        } else {
            regionService.requireAccess(r);
        }
        return generate(month, r, CcConstants.REPORT_TRIGGER_MANUAL);
    }

    /** 定时入口：生成指定月、指定区域（定时线程无主体，回退 system） */
    public RegReportVersion generateScheduled(String month, String region) {
        return generate(month, normRegion(region), CcConstants.REPORT_TRIGGER_SCHEDULED);
    }

    /**
     * 定时幂等扫描：对指定“月+区域”，仅在尚无成功版本时生成；已有任一版本则跳过
     * （重启/重复触发不重复生成；需要更新数据时由人工“重新计算”生成新版本）。
     * @return 结果摘要
     */
    public Map<String, Object> generateScheduledIfAbsent(String month, String region) {
        String r = normRegion(region);
        parseMonth(month);
        RegReport existing = reportMapper.selectOne(new QueryWrapper<RegReport>()
                .eq("report_month", month).eq("supervise_region", r));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("month", month);
        summary.put("region", r.isEmpty() ? "ALL" : r);
        if (existing != null && existing.getCurrentVersion() != null && existing.getCurrentVersion() > 0) {
            summary.put("skipped", true);
            summary.put("currentVersion", existing.getCurrentVersion());
            return summary;
        }
        RegReportVersion v = generate(month, r, CcConstants.REPORT_TRIGGER_SCHEDULED);
        summary.put("skipped", false);
        summary.put("version", v == null ? null : v.getVersionNo());
        summary.put("status", v == null ? CcConstants.REPORT_FAILED : v.getStatus());
        return summary;
    }

    /** 定时按月扫描：默认生成上一自然月的全域汇总报表（供调度器调用） */
    public Map<String, Object> scanMonthly(LocalDateTime now) {
        YearMonth lastMonth = YearMonth.from(now).minusMonths(1);
        return generateScheduledIfAbsent(lastMonth.toString(), "");
    }

    private RegReportVersion generate(String month, String region, String trigger) {
        YearMonth ym = parseMonth(month);
        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();
        Object lock = locks.computeIfAbsent(month + "|" + region, k -> new Object());
        synchronized (lock) {
            // 占位表头：在建表头/取口径之前就失败时，失败链路仍可记录月/区域（reportId 为空）
            RegReport placeholder = new RegReport();
            placeholder.setReportMonth(month);
            placeholder.setSuperviseRegion(region);
            RegReport report = null;
            int attemptNo = 0;
            try {
                RegReportCaliber caliber = caliberService.requireEffective();
                report = repository.findOrCreateReport(month, region, start, end);
                attemptNo = repository.countRuns(report.getId()) + 1;
                return doGenerate(report, caliber, start, end, region, trigger, attemptNo);
            } catch (Exception ex) {
                RegReport target = report;
                if (target == null) {
                    // 失败发生在建表头之前/之时，尝试关联已存在表头
                    target = reportMapper.selectOne(new QueryWrapper<RegReport>()
                            .eq("report_month", month).eq("supervise_region", region));
                }
                if (target == null) {
                    target = placeholder;
                    attemptNo = 1;
                } else if (attemptNo == 0) {
                    attemptNo = repository.countRuns(target.getId()) + 1;
                }
                RegReportCaliber caliber;
                try {
                    caliber = caliberService.currentEffective();
                } catch (Exception ignore) {
                    caliber = null;
                }
                handleFailure(target, month, region, caliber, trigger, attemptNo, ex);
                // 手动入口把失败抛出供接口返回；定时入口由调度器吞掉仅记日志
                if (CcConstants.REPORT_TRIGGER_MANUAL.equals(trigger)) {
                    throw new BusinessException("监管报表生成失败：" + rootMessage(ex)
                            + "（已记录失败运行与告警，可在修正后重新计算生成新版本）");
                }
                log.error("监管报表定时生成失败 month={} region={}", month, region, ex);
                return null;
            } finally {
                locks.remove(month + "|" + region);
            }
        }
    }

    /** 无任何活动的区域/全域：返回零值桶（发运/退货为真实零，无异常样本关闭率 NULL） */
    private RegReportComputeService.RegionMetrics emptyMetrics(String region) {
        RegReportComputeService.RegionMetrics m = new RegReportComputeService.RegionMetrics();
        m.region = region;
        m.shipVolume = 0L;
        m.overtempMinutes = 0L;
        m.exceptionTotal = 0;
        m.exceptionClosed = 0;
        m.exceptionCloseRate = null;
        m.returnQty = 0L;
        m.returnCount = 0;
        return m;
    }

    private RegReportVersion doGenerate(RegReport report, RegReportCaliber caliber,
                                        LocalDateTime start, LocalDateTime end, String region,
                                        String trigger, int attemptNo) {
        List<RegReportComputeService.RegionMetrics> allMetrics =
                computeService.compute(start, end, caliber);
        RegReportComputeService.RegionMetrics metrics = allMetrics.stream()
                .filter(m -> region.equals(m.region == null ? "" : m.region))
                .findFirst()
                .orElseGet(() -> emptyMetrics(region));

        int versionNo = repository.countVersions(report.getId()) + 1;
        RegReportVersion v = new RegReportVersion();
        v.setReportId(report.getId());
        v.setReportNo(report.getReportNo());
        v.setVersionNo(versionNo);
        v.setCaliberId(caliber.getId());
        v.setCaliberVersionNo(caliber.getVersionNo());
        v.setCaliberSnapshot(caliberService.toSnapshotJson(caliber));
        boolean pending = !metrics.missingMetrics.isEmpty();
        v.setStatus(pending ? CcConstants.REPORT_PENDING_SUPPLEMENT : CcConstants.REPORT_GENERATED);
        v.setShipVolume(metrics.shipVolume);
        v.setOvertempMinutes(metrics.overtempMinutes);
        v.setExceptionTotal(metrics.exceptionTotal);
        v.setExceptionClosed(metrics.exceptionClosed);
        v.setExceptionCloseRate(metrics.exceptionCloseRate);
        v.setReturnQty(metrics.returnQty);
        v.setReturnCount(metrics.returnCount);
        v.setMissingMetrics(String.join(",", metrics.missingMetrics));
        v.setPendingNote(metrics.pendingNote);
        v.setTriggerSource(trigger);
        v.setGenerateTime(LocalDateTime.now());
        v.setGenerateByName(UserContext.currentName());
        v.setDownloaded(false);
        v.setFileGenerated(false);
        v.setFileName(fileStore.fileNameOf(report, v));

        List<String> csvLines = csvRenderer.render(report, v);

        RegReportRun run = newRun(report, report.getReportMonth(), report.getSuperviseRegion(),
                attemptNo, trigger, caliber.getVersionNo());
        // 成功路径（版本快照 + 主表推进 + 运行记录 + 版本文件）原子提交；
        // 文件同名已存在会被拒绝覆盖，异常向上走入失败链路（不产生成功版本）。
        repository.persistSuccess(report, v, csvLines, run);

        opLog(CcConstants.ACT_REG_REPORT_GENERATE, report.getId(),
                String.format("监管报表 %s（%s/%s）生成 V%d：状态=%s，发运量=%s，超温时长=%s，"
                                + "异常关闭率=%s（总=%s 关闭=%s），退货=%s；口径 V%d；文件=%s（只追加新版本）",
                        report.getReportNo(), report.getReportMonth(),
                        region.isEmpty() ? "全域" : region, v.getVersionNo(), v.getStatus(),
                        nz(v.getShipVolume()), nz(v.getOvertempMinutes()),
                        v.getExceptionCloseRate() == null ? "待补/无样本" : v.getExceptionCloseRate() + "%",
                        nz(v.getExceptionTotal()), nz(v.getExceptionClosed()), nz(v.getReturnQty()),
                        caliber.getVersionNo(), v.getFileName()));
        return v;
    }

    private void handleFailure(RegReport report, String month, String region, RegReportCaliber caliber,
                               String trigger, int attemptNo, Exception ex) {
        RegReportRun run = newRun(report, month, region, attemptNo, trigger,
                caliber == null ? null : caliber.getVersionNo());
        run.setRunStatus("FAILED");
        run.setErrorType(ex.getClass().getSimpleName());
        run.setErrorMessage(truncate(rootMessage(ex), 1000));
        run.setErrorChain(buildChain(ex));
        run.setErrorStack(buildStack(ex));
        repository.recordRun(run);
        if (report.getId() != null) {
            repository.markLatestFailed(report.getId());
        }
        sendFailureAlert(run, report, caliber, ex);
        opLog(CcConstants.ACT_REG_REPORT_GENERATE, report.getId(),
                String.format("监管报表 %s（%s/%s）第 %d 次生成失败：%s；已保留错误链路并发送告警",
                        report.getReportNo() == null ? "-" : report.getReportNo(), month,
                        region.isEmpty() ? "全域" : region, attemptNo, truncate(rootMessage(ex), 500)));
    }

    /** 失败告警：endpoint 空=站内必达 SUCCESS；非空走可失败通道，失败也只追加流水（不假装成功） */
    private void sendFailureAlert(RegReportRun run, RegReport report, RegReportCaliber caliber, Exception ex) {
        String endpoint = caliber == null ? null : caliber.getNotifyEndpoint();
        String channel = endpoint == null || endpoint.trim().isEmpty()
                ? CcConstants.NOTIFY_CHANNEL_SITE : CcConstants.NOTIFY_CHANNEL_WEBHOOK;
        RegReportAlert alert = new RegReportAlert();
        alert.setRunId(run.getId());
        alert.setReportId(report.getId());
        alert.setReportMonth(run.getReportMonth());
        alert.setChannel(channel);
        alert.setAttemptNo(run.getAttemptNo());
        alert.setAlertTime(LocalDateTime.now());

        if (CcConstants.NOTIFY_CHANNEL_SITE.equals(channel)) {
            alert.setSendStatus(CcConstants.NOTIFY_SUCCESS);
        } else {
            String payload = String.format(
                    "{\"type\":\"REG_REPORT_GENERATE_FAILED\",\"month\":\"%s\",\"region\":\"%s\","
                            + "\"attempt\":%d,\"errorType\":\"%s\",\"error\":\"%s\"}",
                    run.getReportMonth(), normRegion(run.getSuperviseRegion()), run.getAttemptNo(),
                    esc(run.getErrorType()), esc(run.getErrorMessage()));
            ExceptionNotifyTransport.SendResult result = notifyTransport.send(endpoint.trim(), payload);
            alert.setSendStatus(result.isSuccess() ? CcConstants.NOTIFY_SUCCESS : CcConstants.NOTIFY_FAILED);
            if (!result.isSuccess()) {
                alert.setFailReason(truncate(result.getFailReason(), 1000));
            }
        }
        repository.recordAlert(alert);
    }

    // ==================== 查询（列表 / 详情 / 日志 / 运行记录） ====================

    /** 报表列表：管理/全域账号看全部，监管账号按授权区域收窄（含各自区域行；全域行仅全域账号可见） */
    public List<RegReport> listReports(String filterRegion) {
        QueryWrapper<RegReport> qw = new QueryWrapper<RegReport>().orderByDesc("report_month")
                .orderByDesc("id");
        java.util.Set<String> mine = regionService.myRegions();
        boolean global = regionService.isGlobal(mine);
        if (global) {
            if (filterRegion != null && !filterRegion.trim().isEmpty()) {
                qw.eq("supervise_region", filterRegion.trim());
            }
        } else {
            if (mine.isEmpty()) {
                qw.eq("supervise_region", "__NO_REGION__");
            } else if (filterRegion != null && !filterRegion.trim().isEmpty()) {
                String fr = filterRegion.trim();
                if (!mine.contains(fr)) {
                    throw new BusinessException("监管区域隔离：当前账号未被授权区域[" + fr + "]，不能查询该区域报表");
                }
                qw.eq("supervise_region", fr);
            } else {
                qw.in("supervise_region", mine);
            }
        }
        return reportMapper.selectList(qw);
    }

    public RegReport requireReport(Long id) {
        RegReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BusinessException("监管报表不存在: " + id);
        }
        requireReadAccess(report);
        return report;
    }

    private void requireReadAccess(RegReport report) {
        String region = report.getSuperviseRegion();
        if (region != null && !region.isEmpty()) {
            regionService.requireAccess(region);
        }
        // 全域汇总行：仅全域账号可见
        java.util.Set<String> mine = regionService.myRegions();
        if ((region == null || region.isEmpty()) && !regionService.isGlobal(mine)) {
            throw new BusinessException("监管区域隔离：全域汇总监管报表仅全域账号可查看");
        }
    }

    public List<RegReportVersion> listVersions(Long reportId) {
        requireReport(reportId);
        return versionMapper.selectList(new QueryWrapper<RegReportVersion>()
                .eq("report_id", reportId).orderByAsc("version_no"));
    }

    public RegReportVersion requireVersion(Long reportId, Integer versionNo) {
        requireReport(reportId);
        RegReportVersion v = versionMapper.selectOne(new QueryWrapper<RegReportVersion>()
                .eq("report_id", reportId).eq("version_no", versionNo));
        if (v == null) {
            throw new BusinessException(String.format("监管报表 %d 的版本 V%d 不存在", reportId, versionNo));
        }
        return v;
    }

    /** 详情：报表头 + 指定/最新版本 + 全部版本 + 运行记录 + 操作日志（同一口径） */
    public Map<String, Object> detail(Long reportId, Integer versionNo) {
        RegReport report = requireReport(reportId);
        List<RegReportVersion> versions = listVersions(reportId);
        RegReportVersion current = versionNo == null
                ? versions.stream().reduce((a, b) -> b).orElse(null)
                : versions.stream().filter(x -> x.getVersionNo().equals(versionNo)).findFirst()
                        .orElseThrow(() -> new BusinessException("版本不存在: V" + versionNo));
        List<RegReportRun> runs = runMapper.selectList(new QueryWrapper<RegReportRun>()
                .eq("report_id", reportId).orderByAsc("id"));
        List<OpLog> opLogs = opLogMapper.selectList(new QueryWrapper<OpLog>()
                .eq("entity_type", CcConstants.ENT_REG_REPORT).eq("entity_id", reportId)
                .orderByAsc("id"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("report", report);
        m.put("current", current);
        m.put("versions", versions);
        m.put("runs", runs);
        m.put("opLogs", opLogs);
        return m;
    }

    public List<RegReportRun> listRuns(Long reportId) {
        requireReport(reportId);
        return runMapper.selectList(new QueryWrapper<RegReportRun>()
                .eq("report_id", reportId).orderByAsc("id"));
    }

    /** 下载版本快照文件：返回文件字节，并登记下载（只进不退，历史文件不可覆盖） */
    public DownloadFile download(Long reportId, Integer versionNo) {
        RegReport report = requireReport(reportId);
        RegReportVersion version = requireVersion(reportId, versionNo);
        byte[] content = fileStore.readSnapshot(report, version);
        boolean firstDownload = repository.markDownloaded(version);
        if (firstDownload) {
            opLog(CcConstants.ACT_REG_REPORT_DOWNLOAD, reportId,
                    String.format("下载监管报表 %s 版本 V%d 文件 %s（版本化快照，只读）",
                            report.getReportNo(), version.getVersionNo(), version.getFileName()));
        }
        return new DownloadFile(version.getFileName(), content);
    }

    /** 运行记录详情（含完整错误链路/堆栈） */
    public RegReportRun requireRun(Long runId) {
        RegReportRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException("报表生成记录不存在: " + runId);
        }
        if (run.getReportId() != null) {
            requireReport(run.getReportId());
        }
        return run;
    }

    public static class DownloadFile {
        public final String fileName;
        public final byte[] content;

        DownloadFile(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }
    }

    // ==================== 工具 ====================

    private RegReportRun newRun(RegReport report, String month, String region,
                                int attemptNo, String trigger, Integer caliberVersionNo) {
        RegReportRun run = new RegReportRun();
        run.setReportId(report.getId() == null ? null : report.getId());
        run.setReportMonth(month);
        run.setSuperviseRegion(region);
        run.setAttemptNo(attemptNo);
        run.setTriggerSource(trigger);
        run.setCaliberVersionNo(caliberVersionNo);
        run.setRunTime(LocalDateTime.now());
        return run;
    }

    private void opLog(String action, Long entityId, String summary) {
        OpLog logRow = new OpLog();
        logRow.setEntityType(CcConstants.ENT_REG_REPORT);
        logRow.setEntityId(entityId);
        logRow.setAction(action);
        logRow.setSummary(summary);
        logRow.setOperator(UserContext.currentName());
        opLogMapper.insert(logRow);
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw new BusinessException("统计自然月格式必须为 yyyy-MM：" + month);
        }
    }

    private static String normRegion(String region) {
        return region == null ? "" : region.trim();
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
    }

    private static String buildChain(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable t = ex;
        int i = 0;
        while (t != null) {
            sb.append(i++ == 0 ? "ROOT/THROWN: " : "CAUSED BY: ")
                    .append(t.getClass().getName()).append(": ")
                    .append(t.getMessage() == null ? "" : t.getMessage()).append('\n');
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return truncate(sb.toString(), 4000);
    }

    private static String buildStack(Exception ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return truncate(sw.toString(), 8000);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String nz(Number n) {
        return n == null ? "NULL(待补/无样本)" : String.valueOf(n);
    }
}
