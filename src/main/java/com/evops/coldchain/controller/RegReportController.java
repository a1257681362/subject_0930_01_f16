package com.evops.coldchain.controller;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.RegCaliberDraftRequest;
import com.evops.coldchain.dto.RegReportGenerateRequest;
import com.evops.coldchain.entity.RegReportCaliber;
import com.evops.coldchain.entity.RegReportError;
import com.evops.coldchain.service.RegReportCaliberService;
import com.evops.coldchain.service.RegReportGenerateService;
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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 冷链运输监管报表版本化（V16）。
 *
 * 统计口径版本管理（起草/发布，只影响此后生成的报表）；月度报表 Quartz 定时生成首版、
 * 人工重算只追加新版本（绝不覆盖旧版本/已下载文件）；列表/详情/导出同读同一份不可变快照；
 * 关键数据缺失标待补（不静默填零）；生成失败落错误链并告警。导出逐次留痕，可供监管交叉核对。
 */
@RestController
@RequestMapping("/api/cold-chain/reg-reports")
public class RegReportController {

    private final RegReportCaliberService caliberService;
    private final RegReportGenerateService generateService;

    public RegReportController(RegReportCaliberService caliberService,
                               RegReportGenerateService generateService) {
        this.caliberService = caliberService;
        this.generateService = generateService;
    }

    // —— 统计口径版本（版本只追加；调整只影响此后生成的报表）——

    @GetMapping("/calibers")
    public ApiResponse<List<RegReportCaliber>> calibers() {
        return ApiResponse.ok(caliberService.list());
    }

    @PostMapping("/calibers/draft")
    public ApiResponse<RegReportCaliber> draftCaliber(@Valid @RequestBody RegCaliberDraftRequest req) {
        return ApiResponse.ok(caliberService.draft(req));
    }

    @PostMapping("/calibers/{id}/publish")
    public ApiResponse<RegReportCaliber> publishCaliber(@PathVariable("id") Long id) {
        return ApiResponse.ok(caliberService.publish(id));
    }

    // —— 月度监管报表 ——

    /** 报表列表（可按统计月过滤；与详情、导出同读不可变快照） */
    @GetMapping
    public ApiResponse<List<?>> list(@RequestParam(value = "statMonth", required = false) String statMonth) {
        return ApiResponse.ok(generateService.list(statMonth));
    }

    /** 手动生成/重算指定月：无版本出 V1，已有版本只追加下一版本（不覆盖已下载文件） */
    @PostMapping("/generate")
    public ApiResponse<Object> generate(@Valid @RequestBody RegReportGenerateRequest req) {
        RegReportGenerateService.GenerateOutcome outcome = generateService.generateManual(req.getStatMonth());
        return ApiResponse.ok(outcome.report);
    }

    /** 报表详情（快照 + 待补原因 + 下载留痕 + 同统计月错误链，供交叉核对） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(generateService.detail(id));
    }

    /**
     * 报表 CSV 导出（需 coldchain:regreport:export）：只读不可变快照；
     * 文件名含统计月+版本号+报表编号，重算新版本文件名不同不覆盖；逐次写下载留痕。
     */
    @GetMapping("/{id}/export")
    public void export(@PathVariable("id") Long id, HttpServletResponse response) throws java.io.IOException {
        RegReportGenerateService.ExportResult result =
                generateService.export(id, CcConstants.DOWNLOAD_CHANNEL_API);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + result.fileName + "\"");
        // UTF-8 BOM，便于 Excel 正确识别中文
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8));
        writer.write(result.csv);
        writer.flush();
    }

    /** 生成失败错误链台账（可按统计月过滤；保留阶段/类型/消息/堆栈/告警投递结果） */
    @GetMapping("/errors")
    public ApiResponse<List<RegReportError>> errors(
            @RequestParam(value = "statMonth", required = false) String statMonth) {
        return ApiResponse.ok(generateService.listErrors(statMonth));
    }
}
