package com.evops.coldchain.controller;

import com.evops.coldchain.dto.RegCaliberDraftRequest;
import com.evops.coldchain.dto.RegReportGenerateRequest;
import com.evops.coldchain.entity.RegReportVersion;
import com.evops.coldchain.service.RegReportCaliberService;
import com.evops.coldchain.service.RegReportService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 冷链运输监管报表版本化（V16）。
 *
 * 口径版本：管理岗；报表列表/详情/日志：按监管区域隔离的查看权限；
 * 生成/重新计算：生成权限（只追加新版本，不覆盖）；版本文件下载：导出权限。
 */
@RestController
@RequestMapping("/api/cold-chain/reports")
public class RegReportController {

    private final RegReportService reportService;
    private final RegReportCaliberService caliberService;

    public RegReportController(RegReportService reportService, RegReportCaliberService caliberService) {
        this.reportService = reportService;
        this.caliberService = caliberService;
    }

    // —— 统计口径版本（只追加；调整只影响此后生成的报表）——

    @GetMapping("/calibers")
    public ApiResponse<List<?>> calibers() {
        return ApiResponse.ok(caliberService.list());
    }

    @PostMapping("/calibers/draft")
    public ApiResponse<Object> draftCaliber(@Valid @RequestBody RegCaliberDraftRequest req) {
        return ApiResponse.ok(caliberService.draft(req));
    }

    @PostMapping("/calibers/{id}/publish")
    public ApiResponse<Object> publishCaliber(@PathVariable("id") Long id) {
        return ApiResponse.ok(caliberService.publish(id));
    }

    // —— 报表生成 / 重新计算（始终新增版本）——

    /** 手动生成或重新计算指定月/区域报表（重新计算只生成新版本，不覆盖历史/已下载文件） */
    @PostMapping("/generate")
    public ApiResponse<Object> generate(@Valid @RequestBody RegReportGenerateRequest req) {
        return ApiResponse.ok(reportService.generateManual(req.getReportMonth(), req.getSuperviseRegion()));
    }

    /** 手动触发一次按月定时扫描（与 Quartz 定时同一幂等逻辑，运维入口） */
    @PostMapping("/scan")
    public ApiResponse<Map<String, Object>> scan(@RequestParam(required = false) String month,
                                                 @RequestParam(required = false) String region) {
        if (month == null || month.trim().isEmpty()) {
            return ApiResponse.ok(reportService.scanMonthly(LocalDateTime.now()));
        }
        return ApiResponse.ok(reportService.generateScheduledIfAbsent(month.trim(), region));
    }

    // —— 报表台账（列表 / 详情 / 版本 / 运行记录）——

    @GetMapping
    public ApiResponse<List<?>> list(@RequestParam(value = "region", required = false) String region) {
        return ApiResponse.ok(reportService.listReports(region));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id,
                                                   @RequestParam(value = "version", required = false)
                                                   Integer versionNo) {
        return ApiResponse.ok(reportService.detail(id, versionNo));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<?>> versions(@PathVariable("id") Long id) {
        return ApiResponse.ok(reportService.listVersions(id));
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<List<?>> runs(@PathVariable("id") Long id) {
        return ApiResponse.ok(reportService.listRuns(id));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<Object> runDetail(@PathVariable("runId") Long runId) {
        return ApiResponse.ok(reportService.requireRun(runId));
    }

    /**
     * 下载指定版本快照 CSV：版本化文件名，只读，内容与列表/详情/日志同口径；
     * 首次下载登记 downloaded（只进不退），已下载文件绝不被重新计算覆盖。
     */
    @GetMapping("/{id}/versions/{versionNo}/download")
    public void download(@PathVariable("id") Long id,
                         @PathVariable("versionNo") Integer versionNo,
                         HttpServletResponse response) throws IOException {
        RegReportService.DownloadFile file = reportService.download(id, versionNo);
        response.setContentType("text/csv; charset=UTF-8");
        String encoded = URLEncoder.encode(file.fileName, StandardCharsets.UTF_8.name()).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + file.fileName + "\"; filename*=UTF-8''" + encoded);
        // 快照文件已含 UTF-8 BOM（落盘时写入），直接输出不再重复添加
        response.getOutputStream().write(file.content);
        response.getOutputStream().flush();
    }

    /** 便捷：下载最新版本（重定向到最新版本号下载） */
    @GetMapping("/{id}/download")
    public void downloadLatest(@PathVariable("id") Long id, HttpServletResponse response) throws IOException {
        List<RegReportVersion> versions = reportService.listVersions(id);
        RegReportVersion latest = versions.stream().reduce((a, b) -> b).orElse(null);
        if (latest == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "该报表尚无任何版本快照可下载");
            return;
        }
        download(id, latest.getVersionNo(), response);
    }
}
