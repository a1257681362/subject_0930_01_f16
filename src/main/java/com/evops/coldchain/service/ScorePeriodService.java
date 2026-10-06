package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ScorePeriodOpenRequest;
import com.evops.coldchain.entity.ScoreEvidence;
import com.evops.coldchain.entity.ScorePeriod;
import com.evops.coldchain.entity.ScoreRule;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.mapper.ScoreEvidenceMapper;
import com.evops.coldchain.mapper.ScorePeriodMapper;
import com.evops.coldchain.mapper.ScoreSnapshotMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 评分周期（V15）。
 * 开立时绑定当前生效规则版本并固化整版快照——规则调整只影响此后开立的周期；
 * 关闭时对周期内有台账活动的承运商生成不可变评分快照与逐项取证，周期不可重开。
 */
@Service
public class ScorePeriodService {

    @Resource
    private ScorePeriodMapper periodMapper;
    @Resource
    private ScoreSnapshotMapper snapshotMapper;
    @Resource
    private ScoreEvidenceMapper evidenceMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private ScoreRuleService ruleService;
    @Resource
    private ScoreComputeService computeService;
    @Resource
    private OpLogService opLogService;

    /** 开立评分周期：绑定当前生效规则版本（此后规则调整不影响本周期） */
    @Transactional
    public ScorePeriod open(ScorePeriodOpenRequest req) {
        if (!req.getPeriodEnd().isAfter(req.getPeriodStart())) {
            throw new BusinessException("周期结束时间必须晚于开始时间");
        }
        ScoreRule rule = ruleService.requireEffective();
        ScorePeriod period = new ScorePeriod();
        period.setPeriodNo(String.format("ZQ%08d", sequenceMapper.nextScorePeriodNo()));
        period.setPeriodName(req.getPeriodName().trim());
        period.setPeriodStart(req.getPeriodStart());
        period.setPeriodEnd(req.getPeriodEnd());
        period.setStatus(CcConstants.PERIOD_OPEN);
        period.setRuleId(rule.getId());
        period.setRuleVersionNo(rule.getVersionNo());
        period.setRuleSnapshot(ruleService.toSnapshotJson(rule));
        period.setSnapshotCount(0);
        periodMapper.insert(period);

        opLogService.log(CcConstants.ENT_SCORE_PERIOD, period.getId(), null, CcConstants.ACT_SCORE_PERIOD_OPEN,
                String.format("评分周期 %s（%s）开立：%s ~ %s，绑定规则 V%d（%s）；此后规则调整不影响本周期",
                        period.getPeriodNo(), period.getPeriodName(),
                        req.getPeriodStart(), req.getPeriodEnd(),
                        rule.getVersionNo(), rule.getVersionName()));
        return period;
    }

    /**
     * 关闭周期：生成各承运商不可变评分快照 + 逐项取证。
     * 周期 + 承运商唯一约束兜底，重复关闭直接拒绝。
     */
    @Transactional
    public ScorePeriod close(Long periodId) {
        ScorePeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException("评分周期不存在: " + periodId);
        }
        if (CcConstants.PERIOD_CLOSED.equals(period.getStatus())) {
            throw new BusinessException("评分周期 " + period.getPeriodNo() + " 已关闭并生成快照，不可重复关闭");
        }
        ScoreRule rule = ruleService.list().stream()
                .filter(r -> r.getId().equals(period.getRuleId())).findFirst()
                .orElseThrow(() -> new BusinessException("周期绑定的评分规则不存在: " + period.getRuleId()));

        LocalDateTime now = LocalDateTime.now();
        List<ScoreComputeService.CarrierScore> scores = computeService.compute(
                period.getId(), period.getPeriodNo(), period.getPeriodStart(), period.getPeriodEnd(),
                period.getRuleVersionNo(), period.getRuleSnapshot(),
                rule.getOnTimeWeight(), rule.getTempControlWeight(),
                rule.getSignCompleteWeight(), rule.getExceptionRespWeight(), now);

        for (ScoreComputeService.CarrierScore cs : scores) {
            ScoreSnapshot snap = cs.snapshot;
            snapshotMapper.insert(snap);
            for (ScoreEvidence evidence : cs.evidences) {
                evidence.setSnapshotId(snap.getId());
                evidenceMapper.insert(evidence);
            }
        }

        period.setStatus(CcConstants.PERIOD_CLOSED);
        period.setCloseTime(now);
        period.setCloseBy(UserContext.currentId());
        period.setCloseByName(UserContext.currentName());
        period.setSnapshotCount(scores.size());
        periodMapper.updateById(period);

        opLogService.log(CcConstants.ENT_SCORE_PERIOD, period.getId(), null, CcConstants.ACT_SCORE_PERIOD_CLOSE,
                String.format("评分周期 %s 关闭：按规则 V%d 生成 %d 家承运商不可变评分快照；快照与取证只追加，申诉复核结论不回写",
                        period.getPeriodNo(), period.getRuleVersionNo(), scores.size()));
        return period;
    }

    public List<ScorePeriod> list() {
        return periodMapper.selectList(new QueryWrapper<ScorePeriod>().orderByDesc("id"));
    }

    public ScorePeriod require(Long periodId) {
        ScorePeriod period = periodMapper.selectById(periodId);
        if (period == null) {
            throw new BusinessException("评分周期不存在: " + periodId);
        }
        return period;
    }
}
