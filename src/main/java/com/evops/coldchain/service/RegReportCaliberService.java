package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.RegCaliberDraftRequest;
import com.evops.coldchain.entity.RegReportCaliber;
import com.evops.coldchain.mapper.RegReportCaliberMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 监管报表统计口径版本管理（V16）。
 * 版本只追加：草稿 -> 发布热替换（旧生效版本置 SUPERSEDED）；口径调整只影响此后生成的报表，
 * 报表生成时绑定并快照当前生效版本，历史月报表口径不变。
 */
@Service
public class RegReportCaliberService {

    @Resource
    private RegReportCaliberMapper caliberMapper;
    @Resource
    private OpLogService opLogService;

    public RegReportCaliber currentEffective() {
        List<RegReportCaliber> rows = caliberMapper.selectList(new QueryWrapper<RegReportCaliber>()
                .eq("status", CcConstants.EFFECTIVE)
                .orderByDesc("version_no").last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RegReportCaliber requireEffective() {
        RegReportCaliber caliber = currentEffective();
        if (caliber == null) {
            throw new BusinessException("当前无生效的监管报表统计口径版本，请先发布口径");
        }
        return caliber;
    }

    public RegReportCaliber require(Long caliberId) {
        RegReportCaliber caliber = caliberMapper.selectById(caliberId);
        if (caliber == null) {
            throw new BusinessException("监管报表统计口径版本不存在: " + caliberId);
        }
        return caliber;
    }

    public List<RegReportCaliber> list() {
        return caliberMapper.selectList(new QueryWrapper<RegReportCaliber>().orderByAsc("version_no"));
    }

    /** 起草新版本（DRAFT）；同一时刻至多一个未发布草稿 */
    @Transactional
    public RegReportCaliber draft(RegCaliberDraftRequest req) {
        Long dup = caliberMapper.selectCount(new QueryWrapper<RegReportCaliber>()
                .eq("status", CcConstants.ROUTE_DRAFT));
        if (dup != null && dup > 0) {
            throw new BusinessException("已存在未发布的口径草稿，请先发布或调整后再起草");
        }
        Integer maxVersion = caliberMapper.selectList(new QueryWrapper<RegReportCaliber>()
                        .orderByDesc("version_no").last("LIMIT 1")).stream()
                .findFirst().map(RegReportCaliber::getVersionNo).orElse(0);
        RegReportCaliber caliber = new RegReportCaliber();
        caliber.setVersionNo(maxVersion + 1);
        caliber.setVersionName(req.getVersionName().trim());
        caliber.setStatus(CcConstants.ROUTE_DRAFT);
        caliber.setShipmentBasis(blankToDefault(req.getShipmentBasis(), "SHIP_TIME"));
        caliber.setOvertempSource(blankToDefault(req.getOvertempSource(), "TEMP_ALERT"));
        caliber.setCloseWindowDays(req.getCloseWindowDays() == null ? 0 : req.getCloseWindowDays());
        caliber.setReturnBasis(blankToDefault(req.getReturnBasis(), "REGISTER_TIME"));
        caliber.setNotifyEndpoint(blankToNull(req.getNotifyEndpoint()));
        caliber.setChangeReason(req.getChangeReason());
        caliberMapper.insert(caliber);

        opLogService.log(CcConstants.ENT_REG_CALIBER, caliber.getId(), null, CcConstants.ACT_REG_CALIBER_DRAFT,
                String.format("监管报表口径草稿 V%d：%s；发运量=%s，超温时长=%s，异常关闭期限=%d天，退货=%s%s",
                        caliber.getVersionNo(), caliber.getVersionName(),
                        caliber.getShipmentBasis(), caliber.getOvertempSource(),
                        caliber.getCloseWindowDays(), caliber.getReturnBasis(),
                        caliber.getNotifyEndpoint() == null ? "，无告警 webhook（站内留痕）"
                                : "，告警 webhook 已配置"));
        return caliber;
    }

    /** 发布草稿：热替换当前生效版本（置 SUPERSEDED）；只影响此后生成的报表 */
    @Transactional
    public RegReportCaliber publish(Long caliberId) {
        RegReportCaliber caliber = require(caliberId);
        if (!CcConstants.ROUTE_DRAFT.equals(caliber.getStatus())) {
            throw new BusinessException("仅草稿状态的口径版本可发布，当前状态: " + caliber.getStatus());
        }
        RegReportCaliber current = currentEffective();
        if (current != null) {
            current.setStatus(CcConstants.SUPERSEDED);
            caliberMapper.updateById(current);
        }
        caliber.setStatus(CcConstants.EFFECTIVE);
        caliber.setPublishTime(LocalDateTime.now());
        caliber.setPublishBy(UserContext.currentId());
        caliber.setPublishByName(UserContext.currentName());
        caliberMapper.updateById(caliber);

        opLogService.log(CcConstants.ENT_REG_CALIBER, caliber.getId(), null, CcConstants.ACT_REG_CALIBER_PUBLISH,
                String.format("监管报表口径 V%d 发布生效：%s%s；只影响此后生成的月报表",
                        caliber.getVersionNo(), caliber.getVersionName(),
                        current == null ? "" : "，旧版本 V" + current.getVersionNo() + " 已置 SUPERSEDED"));
        return caliber;
    }

    /** 整版口径 JSON 快照（报表生成时固化） */
    public String toSnapshotJson(RegReportCaliber c) {
        String endpoint = c.getNotifyEndpoint() == null ? "" : c.getNotifyEndpoint();
        return String.format(
                "{\"versionNo\":%d,\"versionName\":\"%s\",\"shipmentBasis\":\"%s\",\"overtempSource\":\"%s\","
                        + "\"closeWindowDays\":%d,\"returnBasis\":\"%s\",\"notifyEndpoint\":\"%s\"}",
                c.getVersionNo(), c.getVersionName(), c.getShipmentBasis(), c.getOvertempSource(),
                c.getCloseWindowDays(), c.getReturnBasis(), endpoint.replace("\"", "'"));
    }

    private String blankToDefault(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private String blankToNull(String v) {
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }
}
