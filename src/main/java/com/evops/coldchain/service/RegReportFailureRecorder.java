package com.evops.coldchain.service;

import com.evops.coldchain.entity.RegReportError;
import com.evops.coldchain.mapper.RegReportErrorMapper;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 监管报表生成失败记录器（V16）。
 *
 * 用独立事务（REQUIRES_NEW）落错误链：主生成事务已因异常回滚（不产生报表行），
 * 错误链路必须独立提交、绝不随主事务一起回滚，保证失败事实可追。
 */
@Service
public class RegReportFailureRecorder {

    /** 堆栈最大保留长度（H2 CLOB 足够大，这里仅截断防止极端长度） */
    private static final int MAX_STACK = 8000;
    private static final int MAX_MESSAGE = 4000;

    @Resource
    private RegReportErrorMapper errorMapper;

    /** 独立事务写入一条错误链；返回已落库的错误行ID */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long record(String statMonth, Long caliberId, Integer caliberVersionNo, String triggerSource,
                       String failStage, Throwable error, String alertStatus,
                       String alertTarget, String alertFailReason) {
        RegReportError row = new RegReportError();
        row.setStatMonth(statMonth);
        row.setCaliberId(caliberId);
        row.setCaliberVersionNo(caliberVersionNo);
        row.setTriggerSource(triggerSource);
        row.setFailStage(failStage);
        row.setErrorType(error == null ? failStage : error.getClass().getName());
        row.setErrorMessage(truncate(buildMessage(error), MAX_MESSAGE));
        row.setStackTrace(truncate(buildStackTrace(error), MAX_STACK));
        row.setAlertStatus(alertStatus);
        row.setAlertTarget(alertTarget);
        row.setAlertFailReason(alertFailReason);
        row.setOperator(UserContext.currentName());
        errorMapper.insert(row);
        return row.getId();
    }

    private String buildMessage(Throwable error) {
        if (error == null) {
            return failStageText();
        }
        String msg = error.getMessage();
        return error.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private String failStageText() {
        return "未携带异常对象的失败记录";
    }

    private String buildStackTrace(Throwable error) {
        if (error == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(error).append('\n');
        for (StackTraceElement el : error.getStackTrace()) {
            sb.append("    at ").append(el).append('\n');
            if (sb.length() >= MAX_STACK) {
                break;
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
