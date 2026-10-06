package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ScoreRuleDraftRequest;
import com.evops.coldchain.entity.ScoreRule;
import com.evops.coldchain.mapper.ScoreRuleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评分规则版本管理（V15）。
 * 版本只追加：草稿 -> 发布热替换（旧生效版本置 SUPERSEDED）；
 * 规则调整只影响此后开立的评分周期（周期开立时绑定并快照当前生效版本）。
 */
@Service
public class ScoreRuleService {

    @Resource
    private ScoreRuleMapper ruleMapper;
    @Resource
    private OpLogService opLogService;

    /** 当前生效规则（周期开立时绑定）；无生效规则时拒绝开立周期 */
    public ScoreRule currentEffective() {
        return ruleMapper.selectOne(new QueryWrapper<ScoreRule>()
                .eq("status", CcConstants.EFFECTIVE)
                .orderByDesc("version_no").last("LIMIT 1"));
    }

    public ScoreRule requireEffective() {
        ScoreRule rule = currentEffective();
        if (rule == null) {
            throw new BusinessException("当前无生效的评分规则版本，请先发布规则");
        }
        return rule;
    }

    public List<ScoreRule> list() {
        return ruleMapper.selectList(new QueryWrapper<ScoreRule>().orderByAsc("version_no"));
    }

    /** 起草新版本（DRAFT）；权重合计必须 100 */
    @Transactional
    public ScoreRule draft(ScoreRuleDraftRequest req) {
        int sum = req.getOnTimeWeight() + req.getTempControlWeight()
                + req.getSignCompleteWeight() + req.getExceptionRespWeight();
        if (sum != 100) {
            throw new BusinessException("四项指标权重合计必须等于 100，当前合计: " + sum);
        }
        Long dup = ruleMapper.selectCount(new QueryWrapper<ScoreRule>().eq("status", CcConstants.ROUTE_DRAFT));
        if (dup != null && dup > 0) {
            throw new BusinessException("已存在未发布的规则草稿，请先发布或调整后再起草");
        }
        Integer maxVersion = ruleMapper.selectList(new QueryWrapper<ScoreRule>()
                        .orderByDesc("version_no").last("LIMIT 1")).stream()
                .findFirst().map(ScoreRule::getVersionNo).orElse(0);
        ScoreRule rule = new ScoreRule();
        rule.setVersionNo(maxVersion + 1);
        rule.setVersionName(req.getVersionName().trim());
        rule.setStatus(CcConstants.ROUTE_DRAFT);
        rule.setOnTimeWeight(req.getOnTimeWeight());
        rule.setTempControlWeight(req.getTempControlWeight());
        rule.setSignCompleteWeight(req.getSignCompleteWeight());
        rule.setExceptionRespWeight(req.getExceptionRespWeight());
        rule.setAppealWindowDays(req.getAppealWindowDays());
        rule.setTaskDueDays(req.getTaskDueDays());
        rule.setNotifyEndpoint(req.getNotifyEndpoint());
        rule.setChangeReason(req.getChangeReason());
        ruleMapper.insert(rule);

        opLogService.log(CcConstants.ENT_SCORE_RULE, rule.getId(), null, CcConstants.ACT_SCORE_RULE_DRAFT,
                String.format("评分规则草稿 V%d：%s，权重 准时%d/温控%d/签收%d/异常响应%d，申诉窗口%d天，任务逾期%d天",
                        rule.getVersionNo(), rule.getVersionName(),
                        rule.getOnTimeWeight(), rule.getTempControlWeight(),
                        rule.getSignCompleteWeight(), rule.getExceptionRespWeight(),
                        rule.getAppealWindowDays(), rule.getTaskDueDays()));
        return rule;
    }

    /**
     * 发布草稿：热替换当前生效版本（置 SUPERSEDED）。
     * 只影响此后开立的评分周期——在评周期与已生成快照仍按各自绑定的版本口径。
     */
    @Transactional
    public ScoreRule publish(Long ruleId) {
        ScoreRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("评分规则不存在: " + ruleId);
        }
        if (!CcConstants.ROUTE_DRAFT.equals(rule.getStatus())) {
            throw new BusinessException("仅草稿状态的规则可发布，当前状态: " + rule.getStatus());
        }
        ScoreRule current = currentEffective();
        if (current != null) {
            current.setStatus(CcConstants.SUPERSEDED);
            ruleMapper.updateById(current);
        }
        rule.setStatus(CcConstants.EFFECTIVE);
        rule.setPublishTime(LocalDateTime.now());
        rule.setPublishBy(UserContext.currentId());
        rule.setPublishByName(UserContext.currentName());
        ruleMapper.updateById(rule);

        opLogService.log(CcConstants.ENT_SCORE_RULE, rule.getId(), null, CcConstants.ACT_SCORE_RULE_PUBLISH,
                String.format("评分规则 V%d 发布生效：%s%s；只影响此后开立的评分周期",
                        rule.getVersionNo(), rule.getVersionName(),
                        current == null ? "" : "，旧版本 V" + current.getVersionNo() + " 已置 SUPERSEDED"));
        return rule;
    }

    /** 整版规则 JSON 快照（周期开立/快照生成时固化） */
    public String toSnapshotJson(ScoreRule rule) {
        return String.format(
                "{\"versionNo\":%d,\"versionName\":\"%s\",\"onTimeWeight\":%d,\"tempControlWeight\":%d,"
                        + "\"signCompleteWeight\":%d,\"exceptionRespWeight\":%d,\"appealWindowDays\":%d,"
                        + "\"taskDueDays\":%d}",
                rule.getVersionNo(), rule.getVersionName(),
                rule.getOnTimeWeight(), rule.getTempControlWeight(),
                rule.getSignCompleteWeight(), rule.getExceptionRespWeight(),
                rule.getAppealWindowDays(), rule.getTaskDueDays());
    }
}
