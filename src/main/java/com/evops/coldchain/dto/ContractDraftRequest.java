package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import java.util.List;

/** 起草新费用合同版本（保存为 DRAFT）；费率档与扣罚规则随版本整体提交 */
public class ContractDraftRequest {
    @NotBlank(message = "版本名称不能为空")
    private String versionName;
    private String changeReason;
    private String remark;
    private List<BaseRateItem> baseRates;
    private List<PenaltyRuleItem> penaltyRules;

    public String getVersionName() { return versionName; }
    public void setVersionName(String versionName) { this.versionName = versionName; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public List<BaseRateItem> getBaseRates() { return baseRates; }
    public void setBaseRates(List<BaseRateItem> baseRates) { this.baseRates = baseRates; }
    public List<PenaltyRuleItem> getPenaltyRules() { return penaltyRules; }
    public void setPenaltyRules(List<PenaltyRuleItem> penaltyRules) { this.penaltyRules = penaltyRules; }
}
