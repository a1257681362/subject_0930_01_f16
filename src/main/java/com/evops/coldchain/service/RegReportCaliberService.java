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
 * 版本只追加：草稿 -> 发布热替换（旧生效版本置 SUPERSEDED）；
 * 口径调整只影响此后生成的报表（生成时绑定并固化当前生效版本）。
 */
@Service
public class RegReportCaliberService {

    @Resource
    private RegReportCaliberMapper caliberMapper;
    @Resource
    private OpLogService opLogService;

    public RegReportCaliber currentEffective() {
        return caliberMapper.selectOne(new QueryWrapper<RegReportCaliber>()
                .eq("status", CcConstants.EFFECTIVE)
                .orderByDesc("version_no").last("LIMIT 1"));
    }

    public RegReportCaliber requireEffective() {
        RegReportCaliber caliber = currentEffective();
        if (caliber == null) {
            throw new BusinessException("当前无生效的监管报表统计口径版本，请先发布口径");
        }
        return caliber;
    }

    public List<RegReportCaliber> list() {
        return caliberMapper.selectList(new QueryWrapper<RegReportCaliber>().orderByAsc("version_no"));
    }

    /** 起草新版本（DRAFT）；同一时刻仅允许一个草稿；口径取值必须合法 */
    @Transactional
    public RegReportCaliber draft(RegCaliberDraftRequest req) {
        validateBasis(req.getShipQtyBasis(), req.getOvertempSource(),
                req.getExceptionCloseBasis(), req.getReturnBasis());
        Long dup = caliberMapper.selectCount(new QueryWrapper<RegReportCaliber>()
                .eq("status", CcConstants.ROUTE_DRAFT));
        if (dup != null && dup > 0) {
            throw new BusinessException("已存在未发布的统计口径草稿，请先发布或调整后再起草");
        }
        Integer maxVersion = caliberMapper.selectList(new QueryWrapper<RegReportCaliber>()
                        .orderByDesc("version_no").last("LIMIT 1")).stream()
                .findFirst().map(RegReportCaliber::getVersionNo).orElse(0);

        RegReportCaliber c = new RegReportCaliber();
        c.setVersionNo(maxVersion + 1);
        c.setVersionName(req.getVersionName().trim());
        c.setStatus(CcConstants.ROUTE_DRAFT);
        c.setShipQtyBasis(req.getShipQtyBasis());
        c.setOvertempSource(req.getOvertempSource());
        c.setExceptionCloseBasis(req.getExceptionCloseBasis());
        c.setReturnBasis(req.getReturnBasis());
        c.setIncludeRevoked(req.getIncludeRevoked());
        c.setNotifyEndpoint(trimToNull(req.getNotifyEndpoint()));
        c.setChangeReason(req.getChangeReason());
        caliberMapper.insert(c);

        opLogService.log(CcConstants.ENT_REG_CALIBER, c.getId(), null,
                CcConstants.ACT_REG_CALIBER_DRAFT,
                String.format("监管报表统计口径草稿 V%d：%s（发运=%s，超温=%s，异常关闭=%s，退货=%s，含撤回=%s）",
                        c.getVersionNo(), c.getVersionName(), c.getShipQtyBasis(), c.getOvertempSource(),
                        c.getExceptionCloseBasis(), c.getReturnBasis(), c.getIncludeRevoked()));
        return c;
    }

    /** 发布草稿：热替换当前生效版本；只影响此后生成的报表 */
    @Transactional
    public RegReportCaliber publish(Long id) {
        RegReportCaliber c = caliberMapper.selectById(id);
        if (c == null) {
            throw new BusinessException("统计口径版本不存在: " + id);
        }
        if (!CcConstants.ROUTE_DRAFT.equals(c.getStatus())) {
            throw new BusinessException("仅草稿状态的统计口径可发布，当前状态: " + c.getStatus());
        }
        RegReportCaliber current = currentEffective();
        if (current != null) {
            current.setStatus(CcConstants.SUPERSEDED);
            caliberMapper.updateById(current);
        }
        c.setStatus(CcConstants.EFFECTIVE);
        c.setPublishTime(LocalDateTime.now());
        c.setPublishBy(UserContext.currentId());
        c.setPublishByName(UserContext.currentName());
        caliberMapper.updateById(c);

        opLogService.log(CcConstants.ENT_REG_CALIBER, c.getId(), null,
                CcConstants.ACT_REG_CALIBER_PUBLISH,
                String.format("监管报表统计口径 V%d 发布生效：%s%s；只影响此后生成的报表",
                        c.getVersionNo(), c.getVersionName(),
                        current == null ? "" : "，旧版本 V" + current.getVersionNo() + " 已置 SUPERSEDED"));
        return c;
    }

    private void validateBasis(String ship, String overtemp, String exc, String ret) {
        if (!CcConstants.SHIP_BASIS_BATCH.equals(ship) && !CcConstants.SHIP_BASIS_QTY.equals(ship)) {
            throw new BusinessException("发运量口径非法: " + ship);
        }
        if (!CcConstants.OVERTEMP_SOURCE_ALERT.equals(overtemp)
                && !CcConstants.OVERTEMP_SOURCE_LOG.equals(overtemp)) {
            throw new BusinessException("超温时长口径非法: " + overtemp);
        }
        if (!CcConstants.EXC_CLOSE_BASIS_FOUND.equals(exc)
                && !CcConstants.EXC_CLOSE_BASIS_RESOLVE.equals(exc)) {
            throw new BusinessException("异常关闭率口径非法: " + exc);
        }
        if (!CcConstants.RETURN_BASIS_REGISTER.equals(ret)) {
            throw new BusinessException("退货数量口径非法: " + ret);
        }
    }

    /** 整版口径 JSON 快照（报表版本生成时固化，历史版本口径永不随发布改变） */
    public String toSnapshotJson(RegReportCaliber c) {
        return String.format(
                "{\"versionNo\":%d,\"versionName\":\"%s\",\"shipQtyBasis\":\"%s\",\"overtempSource\":\"%s\","
                        + "\"exceptionCloseBasis\":\"%s\",\"returnBasis\":\"%s\",\"includeRevoked\":%s}",
                c.getVersionNo(), esc(c.getVersionName()), c.getShipQtyBasis(), c.getOvertempSource(),
                c.getExceptionCloseBasis(), c.getReturnBasis(), Boolean.TRUE.equals(c.getIncludeRevoked()));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
