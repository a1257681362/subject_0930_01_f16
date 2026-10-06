package com.evops.coldchain.controller;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.service.RegReportCaliberService;
import com.evops.coldchain.service.RegReportService;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.annotation.Resource;

/**
 * 冷链运输监管报表页面（V16，Thymeleaf 服务端渲染 + fetch 调用报表 API）。
 * 列表/详情按当前账号监管区域收窄；口径维护、生成、下载按钮按 Shiro 权限点显隐。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class RegReportPageController {

    @Resource
    private RegReportService reportService;
    @Resource
    private RegReportCaliberService caliberService;

    @GetMapping("/reports")
    public String reports(@RequestParam(value = "region", required = false) String region, Model model) {
        model.addAttribute("reports", reportService.listReports(region));
        model.addAttribute("calibers", caliberService.list());
        org.apache.shiro.subject.Subject subject = SecurityUtils.getSubject();
        model.addAttribute("canCaliberManage", subject.isPermitted(CcConstants.PERM_REPORT_CALIBER_MANAGE));
        model.addAttribute("canGenerate", subject.isPermitted(CcConstants.PERM_REPORT_GENERATE));
        model.addAttribute("canExport", subject.isPermitted(CcConstants.PERM_REPORT_EXPORT));
        return "coldchain/reports";
    }
}
