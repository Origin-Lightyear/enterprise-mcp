package com.kevin.mcp.cloud.api;

import com.kevin.mcp.cloud.planning.ControlPlanePlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露 Control Plane 的计划生成入口。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@RestController
@Profile("control-plane")
@RequestMapping("/control-plane/planning")
public class ControlPlanePlanningController {

    private final ControlPlanePlanningService planningService;

    /**
     * 注入规划服务。
     *
     * @param planningService 规划服务
     */
    public ControlPlanePlanningController(ControlPlanePlanningService planningService) {
        this.planningService = planningService;
    }

    /**
     * 生成签名执行计划。
     *
     * @param request 规划请求
     * @return 签名执行计划
     * @throws Exception 规划或签名失败
     */
    @PostMapping("/plans")
    public java.util.Map<String, Object> plan(@RequestBody PlanningRequest request) throws Exception {
        return planningService.plan(request);
    }
}
