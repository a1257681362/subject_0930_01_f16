package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ScoreAppealReviewRequest;
import com.evops.coldchain.dto.ScoreAppealSubmitRequest;
import com.evops.coldchain.entity.CarrierAccount;
import com.evops.coldchain.entity.ImproveTask;
import com.evops.coldchain.entity.ScoreAppeal;
import com.evops.coldchain.entity.ScoreEvidence;
import com.evops.coldchain.entity.ScorePeriod;
import com.evops.coldchain.entity.ScoreReview;
import com.evops.coldchain.entity.ScoreRule;
import com.evops.coldchain.entity.ScoreSnapshot;
import com.evops.coldchain.mapper.CarrierAccountMapper;
import com.evops.coldchain.mapper.ImproveTaskMapper;
import com.evops.coldchain.mapper.ScoreAppealMapper;
import com.evops.coldchain.mapper.ScoreEvidenceMapper;
import com.evops.coldchain.mapper.ScorePeriodMapper;
import com.evops.coldchain.mapper.ScoreReviewMapper;
import com.evops.coldchain.mapper.ScoreRuleMapper;
import com.evops.coldchain.mapper.ScoreSnapshotMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 承运商申诉与复核（V15）。
 *
 * - 承运商只能查看/申诉自己的快照（账号 -> 承运商授权）；越权直接拒绝；
 * - 申诉只追加 t_cc_score_appeal，申诉期间原快照分数不回写；
 * - 复核结论（t_cc_score_review，只追加）必须关联证据，可带纠正值（只落复核行）；
 *   复核人与申诉人必须不同账号；复核同时产生改进任务；
 * - 详情视图按“原值 + 最新复核结论”并列给出口径。
 */
@Service
public class ScoreAppealService {

    @Resource
    private ScoreSnapshotMapper snapshotMapper;
    @Resource
    private ScoreAppealMapper appealMapper;
    @Resource
    private ScoreReviewMapper reviewMapper;
    @Resource
    private ScoreEvidenceMapper evidenceMapper;
    @Resource
    private ScorePeriodMapper periodMapper;
    @Resource
    private ScoreRuleMapper ruleMapper;
    @Resource
    private CarrierAccountMapper carrierAccountMapper;
    @Resource
    private ImproveTaskMapper taskMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    /** 当前账号授权的承运商ID（无授权返回 null） */
    public Long carrierIdOf(String username) {
        List<CarrierAccount> rows = carrierAccountMapper.selectList(new QueryWrapper<CarrierAccount>()
                .eq("username", username).eq("status", CcConstants.ACTIVE)
                .orderByAsc("id").last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0).getCarrierId();
    }

    /** 承运商提交申诉：仅本承运商快照、申诉窗口内、同一（快照×指标）仅一条待复核 */
    @Transactional
    public ScoreAppeal submit(ScoreAppealSubmitRequest req) {
        String username = UserContext.currentName();
        Long carrierId = carrierIdOf(username);
        if (carrierId == null) {
            throw new BusinessException("当前账号未授权任何承运商，无法申诉");
        }
        ScoreSnapshot snap = snapshotMapper.selectById(req.getSnapshotId());
        if (snap == null) {
            throw new BusinessException("评分快照不存在: " + req.getSnapshotId());
        }
        if (!snap.getCarrierId().equals(carrierId)) {
            throw new BusinessException("承运商只能申诉自己的评分快照，该快照属于承运商#" + snap.getCarrierId());
        }
        validateMetric(req.getMetricType());
        // 申诉窗口：快照生成时刻 + 周期绑定规则的申诉窗口天数
        ScorePeriod period = periodMapper.selectById(snap.getPeriodId());
        ScoreRule rule = period == null ? null : ruleMapper.selectById(period.getRuleId());
        int windowDays = rule == null || rule.getAppealWindowDays() == null ? 7 : rule.getAppealWindowDays();
        LocalDateTime deadline = snap.getGenerateTime().plusDays(windowDays);
        if (LocalDateTime.now().isAfter(deadline)) {
            throw new BusinessException(String.format(
                    "申诉窗口已关闭：快照生成于 %s，申诉窗口 %d 天（截止 %s）",
                    snap.getGenerateTime(), windowDays, deadline));
        }
        Long pending = appealMapper.selectCount(new QueryWrapper<ScoreAppeal>()
                .eq("snapshot_id", snap.getId())
                .eq("metric_type", req.getMetricType())
                .eq("status", CcConstants.APPEAL_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException("该快照指标 " + req.getMetricType() + " 已存在待复核申诉，请勿重复提交");
        }

        ScoreAppeal appeal = new ScoreAppeal();
        appeal.setAppealNo(String.format("SS%08d", sequenceMapper.nextScoreAppealNo()));
        appeal.setSnapshotId(snap.getId());
        appeal.setPeriodId(snap.getPeriodId());
        appeal.setCarrierId(carrierId);
        appeal.setMetricType(req.getMetricType());
        appeal.setReason(req.getReason().trim());
        appeal.setEvidenceRefs(req.getEvidenceRefs());
        appeal.setStatus(CcConstants.APPEAL_PENDING);
        appeal.setSubmitBy(UserContext.currentId());
        appeal.setSubmitByName(username);
        appeal.setSubmitTime(LocalDateTime.now());
        appealMapper.insert(appeal);

        opLogService.log(CcConstants.ENT_SCORE_APPEAL, appeal.getId(), null, CcConstants.ACT_SCORE_APPEAL_SUBMIT,
                String.format("承运商#%d 对快照#%d 指标 %s 提交申诉 %s：%s（申诉期间原分数不回写）",
                        carrierId, snap.getId(), req.getMetricType(), appeal.getAppealNo(), appeal.getReason()));
        return appeal;
    }

    /**
     * 申诉复核：结论必须关联证据；复核人与申诉人不同账号；
     * 纠正值只落复核行（快照不回写）；复核同时产生改进任务。
     */
    @Transactional
    public ScoreReview review(Long appealId, ScoreAppealReviewRequest req) {
        ScoreAppeal appeal = appealMapper.selectById(appealId);
        if (appeal == null) {
            throw new BusinessException("申诉不存在: " + appealId);
        }
        if (!CcConstants.APPEAL_PENDING.equals(appeal.getStatus())) {
            throw new BusinessException("申诉 " + appeal.getAppealNo() + " 已复核（" + appeal.getStatus() + "），不可重复复核");
        }
        if (!CcConstants.APPEAL_UPHELD.equals(req.getConclusion())
                && !CcConstants.APPEAL_REJECTED.equals(req.getConclusion())) {
            throw new BusinessException("复核结论只能是 UPHELD（成立）或 REJECTED（驳回）: " + req.getConclusion());
        }
        String reviewer = UserContext.currentName();
        if (reviewer.equals(appeal.getSubmitByName())) {
            throw new BusinessException("复核人不能与申诉人相同（职责分离），申诉人: " + reviewer);
        }
        if (req.getTasks() == null || req.getTasks().isEmpty()) {
            throw new BusinessException("复核必须产生至少一条改进任务");
        }

        LocalDateTime now = LocalDateTime.now();
        ScoreReview review = new ScoreReview();
        review.setAppealId(appeal.getId());
        review.setSnapshotId(appeal.getSnapshotId());
        review.setCarrierId(appeal.getCarrierId());
        review.setMetricType(appeal.getMetricType());
        review.setConclusion(req.getConclusion());
        review.setReviewNote(req.getReviewNote().trim());
        review.setEvidenceRefs(req.getEvidenceRefs().trim());
        review.setCorrectedRate(req.getCorrectedRate());
        review.setCorrectedTotalScore(req.getCorrectedTotalScore());
        review.setReviewBy(UserContext.currentId());
        review.setReviewByName(reviewer);
        review.setReviewTime(now);
        reviewMapper.insert(review);

        appeal.setStatus(req.getConclusion());
        appealMapper.updateById(appeal);

        // 复核产生改进任务：逾期天数缺省取周期绑定规则的 taskDueDays
        ScorePeriod period = periodMapper.selectById(appeal.getPeriodId());
        ScoreRule rule = period == null ? null : ruleMapper.selectById(period.getRuleId());
        int defaultDueDays = rule == null || rule.getTaskDueDays() == null ? 3 : rule.getTaskDueDays();
        List<ImproveTask> tasks = new ArrayList<>();
        for (ScoreAppealReviewRequest.ImproveTaskItem item : req.getTasks()) {
            ImproveTask task = new ImproveTask();
            task.setTaskNo(String.format("GJ%08d", sequenceMapper.nextImproveTaskNo()));
            task.setReviewId(review.getId());
            task.setAppealId(appeal.getId());
            task.setSnapshotId(appeal.getSnapshotId());
            task.setCarrierId(appeal.getCarrierId());
            task.setMetricType(appeal.getMetricType());
            task.setTitle(item.getTitle().trim());
            task.setContent(item.getContent().trim());
            task.setOwnerName(item.getOwnerName().trim());
            task.setOwnerAccount(item.getOwnerAccount());
            int dueDays = item.getDueDays() == null ? defaultDueDays : item.getDueDays();
            if (dueDays <= 0) {
                throw new BusinessException("改进任务逾期天数必须为正: " + dueDays);
            }
            task.setDueTime(now.plusDays(dueDays));
            task.setStatus(CcConstants.TASK_PENDING);
            task.setRemindStatus(CcConstants.REMIND_NONE);
            taskMapper.insert(task);
            tasks.add(task);

            opLogService.log(CcConstants.ENT_IMPROVE_TASK, task.getId(), null, CcConstants.ACT_IMPROVE_TASK_CREATE,
                    String.format("复核产生改进任务 %s：%s，负责人=%s，截止 %s（申诉 %s / 指标 %s）",
                            task.getTaskNo(), task.getTitle(), task.getOwnerName(),
                            task.getDueTime(), appeal.getAppealNo(), appeal.getMetricType()));
        }

        opLogService.log(CcConstants.ENT_SCORE_APPEAL, appeal.getId(), null, CcConstants.ACT_SCORE_APPEAL_REVIEW,
                String.format("申诉 %s 复核 %s：复核人=%s，关联证据=%s，纠正值=%s/%s，产生改进任务 %d 条；原快照分数不回写",
                        appeal.getAppealNo(), req.getConclusion(), reviewer, review.getEvidenceRefs(),
                        req.getCorrectedRate(), req.getCorrectedTotalScore(), tasks.size()));
        return review;
    }

    /** 快照详情（管理岗全量）：快照 + 逐项取证 + 申诉与复核结论（原值与复核口径并列） */
    public Map<String, Object> detail(Long snapshotId) {
        ScoreSnapshot snap = snapshotMapper.selectById(snapshotId);
        if (snap == null) {
            throw new BusinessException("评分快照不存在: " + snapshotId);
        }
        return detailView(snap);
    }

    /** 承运商视角快照详情：只能看自己的 */
    public Map<String, Object> myDetail(Long snapshotId) {
        String username = UserContext.currentName();
        Long carrierId = carrierIdOf(username);
        ScoreSnapshot snap = snapshotMapper.selectById(snapshotId);
        if (snap == null) {
            throw new BusinessException("评分快照不存在: " + snapshotId);
        }
        if (carrierId == null || !snap.getCarrierId().equals(carrierId)) {
            throw new BusinessException("承运商只能查看自己的评分快照");
        }
        return detailView(snap);
    }

    private Map<String, Object> detailView(ScoreSnapshot snap) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("snapshot", snap);
        List<ScoreEvidence> evidences = evidenceMapper.selectList(new QueryWrapper<ScoreEvidence>()
                .eq("snapshot_id", snap.getId()).orderByAsc("id"));
        view.put("evidences", evidences);
        List<ScoreAppeal> appeals = appealMapper.selectList(new QueryWrapper<ScoreAppeal>()
                .eq("snapshot_id", snap.getId()).orderByAsc("id"));
        List<Map<String, Object>> appealViews = new ArrayList<>();
        for (ScoreAppeal appeal : appeals) {
            Map<String, Object> av = new LinkedHashMap<>();
            av.put("appeal", appeal);
            av.put("reviews", reviewMapper.selectList(new QueryWrapper<ScoreReview>()
                    .eq("appeal_id", appeal.getId()).orderByAsc("id")));
            appealViews.add(av);
        }
        view.put("appeals", appealViews);
        view.put("appealWindow", appealWindow(snap));
        return view;
    }

    /** 申诉入口状态：窗口天数、截止时间、当前是否可申诉 */
    public Map<String, Object> appealWindow(ScoreSnapshot snap) {
        ScorePeriod period = periodMapper.selectById(snap.getPeriodId());
        ScoreRule rule = period == null ? null : ruleMapper.selectById(period.getRuleId());
        int windowDays = rule == null || rule.getAppealWindowDays() == null ? 7 : rule.getAppealWindowDays();
        LocalDateTime deadline = snap.getGenerateTime().plusDays(windowDays);
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("windowDays", windowDays);
        w.put("deadline", deadline);
        w.put("open", !LocalDateTime.now().isAfter(deadline));
        return w;
    }

    /** 承运商自己的评分台账：只含本承运商快照 + 申诉入口状态 */
    public List<Map<String, Object>> mySnapshots() {
        String username = UserContext.currentName();
        Long carrierId = carrierIdOf(username);
        if (carrierId == null) {
            throw new BusinessException("当前账号未授权任何承运商，无法查看评分");
        }
        List<ScoreSnapshot> snaps = snapshotMapper.selectList(new QueryWrapper<ScoreSnapshot>()
                .eq("carrier_id", carrierId).orderByDesc("id"));
        List<Map<String, Object>> views = new ArrayList<>();
        for (ScoreSnapshot snap : snaps) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("snapshot", snap);
            v.put("appealWindow", appealWindow(snap));
            v.put("appeals", appealMapper.selectList(new QueryWrapper<ScoreAppeal>()
                    .eq("snapshot_id", snap.getId()).orderByAsc("id")));
            views.add(v);
        }
        return views;
    }

    /** 管理岗全量快照台账 */
    public List<ScoreSnapshot> listSnapshots() {
        return snapshotMapper.selectList(new QueryWrapper<ScoreSnapshot>().orderByDesc("id"));
    }

    /** 申诉列表（管理岗全量；承运商申诉入口由 /scores/my 随快照回带） */
    public List<ScoreAppeal> listAppeals() {
        return appealMapper.selectList(new QueryWrapper<ScoreAppeal>().orderByDesc("id"));
    }

    /** 申诉详情（含复核结论）：承运商只能看自己的申诉 */
    public Map<String, Object> appealDetail(Long appealId) {
        ScoreAppeal appeal = requireAppeal(appealId);
        String username = UserContext.currentName();
        Long carrierId = carrierIdOf(username);
        if (carrierId != null && !appeal.getCarrierId().equals(carrierId)) {
            throw new BusinessException("承运商只能查看自己的申诉");
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("appeal", appeal);
        view.put("reviews", reviewMapper.selectList(new QueryWrapper<ScoreReview>()
                .eq("appeal_id", appealId).orderByAsc("id")));
        return view;
    }

    public ScoreAppeal requireAppeal(Long appealId) {
        ScoreAppeal appeal = appealMapper.selectById(appealId);
        if (appeal == null) {
            throw new BusinessException("申诉不存在: " + appealId);
        }
        return appeal;
    }

    private void validateMetric(String metricType) {
        if (!CcConstants.METRIC_ON_TIME.equals(metricType)
                && !CcConstants.METRIC_TEMP_CONTROL.equals(metricType)
                && !CcConstants.METRIC_SIGN_COMPLETE.equals(metricType)
                && !CcConstants.METRIC_EXCEPTION_RESPONSE.equals(metricType)) {
            throw new BusinessException("未知申诉指标类型: " + metricType
                    + "（可选 ON_TIME/TEMP_CONTROL/SIGN_COMPLETE/EXCEPTION_RESPONSE）");
        }
    }
}
