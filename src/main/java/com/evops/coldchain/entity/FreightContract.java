package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 运输费用合同版本（V14）。
 * 同一合同号 contract_no 下版本号递增，版本行只追加：DRAFT 草稿 / EFFECTIVE 生效中 /
 * SUPERSEDED 被新版本替换。发布即热替换旧生效版本，只影响此后生成的结算；
 * 已生成结算单持有规则快照（rule_snapshot），与合同后续演进脱钩。
 */
@TableName("t_cc_freight_contract")
public class FreightContract extends BaseEntity {
    private String contractNo;
    private Integer versionNo;
    private String versionName;
    private String status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime publishTime;
    private Long publishBy;
    private String publishByName;
    private String changeReason;
    private String remark;

    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDateTime effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public Long getPublishBy() { return publishBy; }
    public void setPublishBy(Long publishBy) { this.publishBy = publishBy; }
    public String getPublishByName() { return publishByName; }
    public void setPublishByName(String publishByName) { this.publishByName = publishByName; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
