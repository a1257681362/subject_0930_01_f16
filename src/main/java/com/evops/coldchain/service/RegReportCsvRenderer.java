package com.evops.coldchain.service;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.RegReport;
import com.evops.coldchain.entity.RegReportVersion;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 监管报表版本快照 CSV 渲染（V16）。
 * 内容完全取自已持久化的版本快照（不实时重算），保证导出文件与列表/详情/日志同口径、可复核。
 */
@Component
public class RegReportCsvRenderer {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 渲染单个版本快照为 CSV 行（不含 BOM，由落盘/下载侧添加） */
    public List<String> render(RegReport report, RegReportVersion v) {
        boolean shipByQty = v.getCaliberSnapshot() != null
                && v.getCaliberSnapshot().contains("\"shipQtyBasis\":\"SHIP_QTY\"");
        List<String> missing = v.getMissingMetrics() == null || v.getMissingMetrics().isEmpty()
                ? new ArrayList<>() : Arrays.asList(v.getMissingMetrics().split(","));

        List<String> lines = new ArrayList<>();
        lines.add("# 冷链运输监管报表（版本化快照，只读导出）");
        lines.add("# 报表编号=" + report.getReportNo());
        lines.add("# 统计自然月=" + report.getReportMonth());
        lines.add("# 监管区域=" + (report.getSuperviseRegion() == null || report.getSuperviseRegion().isEmpty()
                ? "全域汇总" : report.getSuperviseRegion()));
        lines.add("# 版本=V" + v.getVersionNo()
                + "（重新计算只追加新版本，不覆盖历史版本/已下载文件）");
        lines.add("# 统计口径版本=V" + v.getCaliberVersionNo());
        lines.add("# 口径快照=" + v.getCaliberSnapshot());
        lines.add("# 生成时间=" + (v.getGenerateTime() == null ? "" : v.getGenerateTime().format(FMT))
                + "  生成人=" + nz(v.getGenerateByName()));
        lines.add("# 触发来源=" + v.getTriggerSource()
                + "  版本状态=" + v.getStatus());
        if (v.getPendingNote() != null && !v.getPendingNote().isEmpty()) {
            lines.add("# 待补说明=" + v.getPendingNote());
        }

        lines.add(row("指标", "数值", "数据状态", "说明"));
        lines.add(row("发运量", str(v.getShipVolume()), "OK",
                shipByQty ? "单位：件" : "单位：批次"));
        lines.add(row("超温时长（分钟）", str(v.getOvertempMinutes()),
                missing.contains(CcConstants.METRIC_OVERTEMP) ? "PENDING_SUPPLEMENT" : "OK",
                missing.contains(CcConstants.METRIC_OVERTEMP)
                        ? "温度留痕缺失，待补（未填零）" : "连续超温时长合计"));
        String closeStatus;
        String closeNote;
        if (missing.contains(CcConstants.METRIC_EXC_CLOSE)) {
            closeStatus = "PENDING_SUPPLEMENT";
            closeNote = "异常归属时间缺失，关闭率待补（未填零）";
        } else if (v.getExceptionCloseRate() == null) {
            closeStatus = "NO_SAMPLE";
            closeNote = "当月无异常样本，关闭率记 NULL（不填零）";
        } else {
            closeStatus = "OK";
            closeNote = String.format("异常总数=%d，已关闭=%d",
                    nz(v.getExceptionTotal()), nz(v.getExceptionClosed()));
        }
        lines.add(row("异常关闭率（%）", str(v.getExceptionCloseRate()), closeStatus, closeNote));
        lines.add(row("退货数量（件）", str(v.getReturnQty()), "OK",
                "退货笔数=" + nz(v.getReturnCount())));
        return lines;
    }

    private static String row(String a, String b, String c, String d) {
        return esc(a) + "," + esc(b) + "," + esc(c) + "," + esc(d);
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }
}
