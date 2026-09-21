package com.customer.mcp.wren.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 将管理控制台路由转发到 Fat JAR 内的 React 入口。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@Controller
public class WrenAdminPageController {

    /**
     * 打开 Wren 管理控制台。
     *
     * @return React 入口转发路径
     */
    @GetMapping({"/admin/wren", "/admin/wren/"})
    public String index() {
        return "forward:/admin/wren/index.html";
    }
}
