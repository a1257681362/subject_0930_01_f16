package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.RegReport;
import com.evops.coldchain.entity.RegReportAlert;
import com.evops.coldchain.entity.RegReportRun;
import com.evops.coldchain.entity.RegReportVersion;
import com.evops.coldchain.mapper.RegReportAlertMapper;
import com.evops.coldchain.mapper.RegReportMapper;
import com.evops.coldchain.mapper.RegReportRunMapper;
import com.evops.coldchain.mapper.RegReportVersionMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.UserContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 监管报表持久化事务边界（V16）。
 *
 * 成功路径（版本快照 + 主表推进 + 成功运行记录 + 版本文件落盘）一个事务原子提交；
 * 失败运行记录 / 失败告警用独立新事务写入，保证主流程回滚后错误链路仍保留。
 */
@Component
public class RegReportRepository {

    @Resource
    private RegReportMapper reportMapper;
    @Resource
    private RegReportVersionMapper versionMapper;
    @Resource
    private RegReportRunMapper runMapper;
    @Resource
    private RegReportAlertMapper alertMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private RegReportFileStore fileStore;

    /** 按“自然月+区域”取报表，不存在则建表头（唯一约束兜底并发） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegReport findOrCreateReport(String month, String region,
                                        LocalDateTime start, LocalDateTime end) {
        RegReport existing = reportMapper.selectOne(new QueryWrapper<RegReport>()
                .eq("report_month", month).eq("supervise_region", region));
        if (existing != null) {
            return existing;
        }
        RegReport report = new RegReport();
        report.setReportNo(String.format("JG%08d", sequenceMapper.nextRegReportNo()));
        report.setReportMonth(month);
        report.setSuperviseRegion(region);
        report.setPeriodStart(start);
        report.setPeriodEnd(end);
        report.setCurrentVersion(0);
        try {
            reportMapper.insert(report);
            return report;
        } catch (DuplicateKeyException e) {
            return reportMapper.selectOne(new QueryWrapper<RegReport>()
                    .eq("report_month", month).eq("supervise_region", region));
        }
    }

    public int countRuns(Long reportId) {
        Long c = runMapper.selectCount(new QueryWrapper<RegReportRun>().eq("report_id", reportId));
        return c == null ? 0 : c.intValue();
    }

    public int countVersions(Long reportId) {
        Long c = versionMapper.selectCount(new QueryWrapper<RegReportVersion>().eq("report_id", reportId));
        return c == null ? 0 : c.intValue();
    }

    /**
     * 成功路径原子提交：插入不可变版本快照 -> 推进主表当前版本 -> 写成功运行记录 -> 落版本文件。
     * 文件在数据库操作之后、事务提交前写入；任何一步失败整体回滚（不产生成功版本）。
     * 文件同名已存在时 writeSnapshot 拒绝覆盖（重新计算只能新版本）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegReportVersion persistSuccess(RegReport report, RegReportVersion version,
                                           List<String> csvLines, RegReportRun run) {
        versionMapper.insert(version);

        report.setCurrentVersion(version.getVersionNo());
        report.setLatestStatus(version.getStatus());
        reportMapper.updateById(report);

        run.setVersionId(version.getId());
        run.setRunStatus("SUCCESS");
        runMapper.insert(run);

        // 版本化文件落盘（独立新文件名，绝不覆盖历史版本）；失败则上面的数据库改动一并回滚
        fileStore.writeSnapshot(report, version, csvLines);
        version.setFileGenerated(true);
        versionMapper.updateById(version);
        return version;
    }

    /** 失败运行记录独立事务（主流程回滚不影响错误链路留存） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegReportRun recordRun(RegReportRun run) {
        runMapper.insert(run);
        return run;
    }

    /** 失败告警流水独立事务 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegReportAlert recordAlert(RegReportAlert alert) {
        alertMapper.insert(alert);
        return alert;
    }

    /** 主表最新状态置失败（不改变 currentVersion） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markLatestFailed(Long reportId) {
        RegReport report = reportMapper.selectById(reportId);
        if (report != null) {
            report.setLatestStatus(CcConstants.REPORT_FAILED);
            reportMapper.updateById(report);
        }
    }

    /** 下载登记（只进不退）：首次下载置 downloaded=TRUE */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDownloaded(RegReportVersion version) {
        if (Boolean.TRUE.equals(version.getDownloaded())) {
            return false;
        }
        version.setDownloaded(true);
        version.setDownloadTime(LocalDateTime.now());
        version.setDownloadByName(UserContext.currentName());
        versionMapper.updateById(version);
        return true;
    }
}
