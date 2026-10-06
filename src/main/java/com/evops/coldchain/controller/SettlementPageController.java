package com.evops.coldchain.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.FreightContractService;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 冷链运输费用预结算页面（V14，Thymeleaf 服务端渲染 + fetch 调用费用 API）。
 * 页面字段范围与后台同口径：财务可见费率/单价/规则快照与审核操作，
 * 运营只见业务依据、金额与争议操作（由 viewerScope 控制显隐）。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class SettlementPageController {

    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private FreightContractService contractService;

    @GetMapping("/settlements")
    public String settlements(Model model) {
        // 只列已签收批次（预结算前置状态），最近 100 条
        List<TransportBatch> batches = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .orderByDesc("id").last("LIMIT 200")).stream()
                .filter(b -> CcConstants.BATCH_SIGNED.equals(b.getStatus()))
                .collect(Collectors.toList());
        model.addAttribute("batches", batches);
        model.addAttribute("contracts", contractService.listVersions());
        org.apache.shiro.subject.Subject subject = SecurityUtils.getSubject();
        model.addAttribute("canManageContract", subject.isPermitted(CcConstants.PERM_FREIGHT_CONTRACT_MANAGE));
        model.addAttribute("canGenerate", subject.isPermitted(CcConstants.PERM_FREIGHT_SETTLE_GENERATE));
        model.addAttribute("canDispute", subject.isPermitted(CcConstants.PERM_FREIGHT_SETTLE_DISPUTE));
        model.addAttribute("canReview", subject.isPermitted(CcConstants.PERM_FREIGHT_SETTLE_REVIEW));
        model.addAttribute("canAudit", subject.isPermitted(CcConstants.PERM_FREIGHT_SETTLE_AUDIT));
        model.addAttribute("canReplay", subject.isPermitted(CcConstants.PERM_FREIGHT_REPLAY));
        model.addAttribute("financeScope",
                subject.hasRole(CcConstants.ROLE_FINANCE)
                        || subject.isPermitted(CcConstants.PERM_FREIGHT_SETTLE_AUDIT)
                        || subject.isPermitted(CcConstants.PERM_FREIGHT_SETTLE_REVIEW));
        return "coldchain/settlements";
    }
}
