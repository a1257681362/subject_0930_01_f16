package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.RegReport;
import com.evops.coldchain.entity.RegReportCaliber;
import com.evops.coldchain.mapper.RegReportMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 监管报表版本快照持久化（V16）。
 *
 * 独立 Bean + 事务边界：由 {@link RegReportGenerateService} 在 try/catch 中跨 Bean 调用，
 * 保证“取下一版本号 + 插入不可变快照 + 生成留痕”原子提交；一旦抛错整事务回滚（不留半截报表行），
 * 由编排服务落错误链。唯一约束（统计月+版本号）兜底并发重算绝不覆盖旧版本。
 */
@Service
public class RegReportVersionPersister {

    @Resource
    private RegReportMapper reportMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private RegReportCaliberService caliberService;
    @Resource
    private OpLogService opLogService;

    @Transactional
    public RegReport persist(YearMonth month, LocalDateTime start, LocalDateTime end,
                             RegReportCaliber caliber, String triggerSource,
                             RegReportComputeService.MetricBundle bundle) {
        String statMonth = month.toString();
        Integer maxVersion = reportMapper.selectList(new QueryWrapper<RegReport>()
                        .eq("stat_month", statMonth).orderByDesc("version_no").last("LIMIT 1")).stream()
                .findFirst().map(RegReport::getVersionNo).orElse(0);

        RegReport r = new RegReport();
        r.setReportNo(String.format("JG%08d", sequenceMapper.nextRegReportNo()));
        r.setStatMonth(statMonth);
        r.setVersionNo(maxVersion + 1);
        r.setStatus(bundle.isPending() ? CcConstants.REPORT_PENDING : CcConstants.REPORT_SUCCESS);
        r.setCaliberId(caliber.getId());
        r.setCaliberVersionNo(caliber.getVersionNo());
        r.setCaliberSnapshot(caliberService.toSnapshotJson(caliber));
        r.setPeriodStart(start);
        r.setPeriodEnd(end);
        r.setTriggerSource(triggerSource);
        r.setGenerateTime(LocalDateTime.now());
        r.setGeneratedBy(UserContext.currentId());
        r.setGeneratedByName(UserContext.currentName());

        r.setShippedBatchCount(bundle.shippedBatchCount);
        r.setShipQtyTotal(bundle.shipQtyTotal);
        r.setOvertempMinutes(bundle.overtempMinutes);
        r.setExceptionTotalCount(bundle.exceptionTotalCount);
        r.setExceptionClosedCount(bundle.exceptionClosedCount);
        r.setExceptionCloseRate(bundle.exceptionCloseRate);
        r.setReturnQtyTotal(bundle.returnQtyTotal);

        List<String> pendingKeys = new ArrayList<>(bundle.pendingReasons.keySet());
        r.setPendingMetrics(String.join(",", pendingKeys));
        r.setSupplementNote(bundle.isPending() ? toNoteJson(bundle.pendingReasons).replace('\n', ' ') : "");

        reportMapper.insert(r);

        opLogService.log(CcConstants.ENT_REG_REPORT, r.getId(), null, CcConstants.ACT_REG_REPORT_GENERATE,
                String.format("监管报表 %s（%s V%d）按口径 V%d 生成：触发=%s，状态=%s%s",
                        r.getReportNo(), statMonth, r.getVersionNo(),
                        caliber.getVersionNo(), triggerSource, r.getStatus(),
                        bundle.isPending() ? "；待补指标=" + r.getPendingMetrics() : "；四项指标数据齐备"));
        return r;
    }

    private String toNoteJson(java.util.Map<String, String> reasons) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> e : reasons.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(e.getKey()).append("\":\"").append(escapeJson(e.getValue())).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
    }
}
