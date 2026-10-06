package com.evops.coldchain.service;

import com.evops.coldchain.entity.RegReport;
import com.evops.coldchain.entity.RegReportVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 监管报表版本文件快照存储（V16）。
 *
 * 每个报表版本生成时把 CSV 快照落盘到独立的版本化文件，文件名含报表号与版本号；
 * 已存在的同名文件绝不覆盖（版本只追加）。下载即读取该不可变快照，
 * 与列表/详情/日志同源，保证“不能覆盖已下载文件”。
 */
@Component
public class RegReportFileStore {

    private final Path baseDir;

    public RegReportFileStore(
            @Value("${coldchain.reg-report.export-dir:./data/reg-reports}") String exportDir) {
        this.baseDir = Paths.get(exportDir);
    }

    /** 生成版本化文件名（ASCII，便于 Content-Disposition）：reg_report_JG00000001_v1.csv */
    public String fileNameOf(RegReport report, RegReportVersion version) {
        return String.format("reg_report_%s_v%d.csv", report.getReportNo(), version.getVersionNo());
    }

    /**
     * 写版本快照文件；若同名文件已存在则拒绝（绝不覆盖历史/已下载文件）。
     *
     * @return 写入文件的绝对路径
     */
    public synchronized Path writeSnapshot(RegReport report, RegReportVersion version, List<String> csvLines) {
        try {
            Files.createDirectories(baseDir);
            String fileName = fileNameOf(report, version);
            Path target = baseDir.resolve(fileName);
            if (Files.exists(target)) {
                throw new IllegalStateException("报表版本文件已存在，拒绝覆盖: " + fileName
                        + "（重新计算只能生成新版本）");
            }
            StringBuilder sb = new StringBuilder();
            // UTF-8 BOM，便于 Excel 识别中文
            sb.append('﻿');
            for (String line : csvLines) {
                sb.append(line).append('\n');
            }
            Files.write(target, sb.toString().getBytes(StandardCharsets.UTF_8));
            return target.toAbsolutePath();
        } catch (IOException e) {
            throw new IllegalStateException("写监管报表快照文件失败: " + e.getMessage(), e);
        }
    }

    /** 读取版本快照文件内容（下载用）；文件缺失抛业务异常 */
    public byte[] readSnapshot(RegReport report, RegReportVersion version) {
        Path target = baseDir.resolve(fileNameOf(report, version));
        if (!Files.exists(target)) {
            throw new IllegalStateException("报表版本快照文件不存在或已被外部移除: " + fileNameOf(report, version));
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("读取监管报表快照文件失败: " + e.getMessage(), e);
        }
    }
}
