package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 承运商账号授权（V15）：账号 -> 承运商。
 * 承运商只能查看自己的评分快照与申诉入口；仿 V7 监管区域授权口径。
 */
@TableName("t_cc_carrier_account")
public class CarrierAccount extends BaseEntity {
    private String username;
    private Long carrierId;
    /** ACTIVE / REVOKED */
    private String status;
    private String remark;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Long getCarrierId() { return carrierId; }
    public void setCarrierId(Long carrierId) { this.carrierId = carrierId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
