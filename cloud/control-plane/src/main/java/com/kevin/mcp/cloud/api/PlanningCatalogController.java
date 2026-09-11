package com.kevin.mcp.cloud.api;

import com.kevin.mcp.cloud.catalog.PlanningCatalogService;
import com.kevin.mcp.cloud.catalog.RuntimeRegistration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露 Control Plane 的目录发布和 Runtime 注册接口。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@RestController
@Profile("control-plane")
@RequestMapping("/control-plane")
public class PlanningCatalogController {

    private final PlanningCatalogService catalogService;

    /**
     * 注入目录服务。
     *
     * @param catalogService 目录服务
     */
    public PlanningCatalogController(PlanningCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * 登记客户 Runtime 实际版本。
     *
     * @param registration Runtime 注册信息
     * @return 已保存注册信息
     */
    @PostMapping("/runtime-registrations")
    public RuntimeRegistration register(@RequestBody RuntimeRegistration registration) {
        return catalogService.registerRuntime(registration);
    }

}
