package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 逾期提醒去重登记（V15）：task_id 唯一约束兜底。
 * 提醒发送成功同事务写入；外部依赖恢复后恰好一条成功通知，不重复。
 */
@TableName("t_cc_improve_remind_dedup")
public class ImproveRemindDedup extends CreateOnlyEntity {
    private Long taskId;
    private Long notifyLogId;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getNotifyLogId() { return notifyLogId; }
    public void setNotifyLogId(Long notifyLogId) { this.notifyLogId = notifyLogId; }
}
