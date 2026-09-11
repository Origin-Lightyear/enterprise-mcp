package com.kevin.mcp.cloud.planning;

import com.kevin.mcp.cloud.api.PlanningRequest;
import com.kevin.mcp.cloud.catalog.PlanningCatalog;
import com.kevin.mcp.cloud.catalog.PlanningCatalogService;
import com.kevin.mcp.cloud.configuration.ControlPlanePlanningProperties;
import com.kevin.mcp.cloud.employee.EmployeeLlmConfigService;
import com.kevin.mcp.cloud.employee.EmployeeLlmInvocationConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根据已激活规划目录调用模型并生成执行计划。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Service
@Profile("control-plane")
public class ControlPlanePlanningService {

    private final PlanningCatalogService catalogService;
    private final ControlPlanePlanningProperties properties;
    private final ExecutionPlanGenerator planGenerator;
    private final EmployeeLlmConfigService employeeLlmConfigService;

    /**
     * 注入目录、模型和签名配置。
     *
     * @param catalogService 规划目录服务
     * @param properties Control Plane 配置
     * @param planGenerator 云端执行计划生成器
     * @param employeeLlmConfigService 员工 LLM 配置服务
     */
    public ControlPlanePlanningService(PlanningCatalogService catalogService,
                                       ControlPlanePlanningProperties properties,
                                       ExecutionPlanGenerator planGenerator,
                                       EmployeeLlmConfigService employeeLlmConfigService) {
        this.catalogService = catalogService;
        this.properties = properties;
        this.planGenerator = planGenerator;
        this.employeeLlmConfigService = employeeLlmConfigService;
    }

    /**
     * 根据 ACTIVE 目录生成绑定当前 Runtime 的执行计划。
     *
     * @param request 规划请求
     * @return 执行计划
     * @throws Exception 目录或模型调用失败
     */
    public Map<String, Object> plan(PlanningRequest request) throws Exception {
        validateRequest(request);
        PlanningCatalog catalog = catalogService.find(request.tenantId(), request.artifactDigest())
                .orElseThrow(() -> new IllegalStateException("Planning catalog not found for artifact"));
        validateBinding(request, catalog);
        EmployeeLlmInvocationConfig invocationConfig = employeeLlmConfigService.getConfig(
                request.tenantId(), request.employeeId());
        Map<String, Object> modelPlan = planGenerator.generate(
                request.intent(), catalog.catalogJson(), invocationConfig);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("planId", java.util.UUID.randomUUID().toString());
        envelope.put("instanceId", request.instanceId());
        envelope.put("artifactVersion", request.artifactVersion());
        envelope.put("artifactDigest", request.artifactDigest());
        envelope.put("tenantId", request.tenantId());
        envelope.put("employeeId", request.employeeId());
        envelope.putAll(modelPlan);
        return envelope;
    }

    /** 校验请求身份必须与已登记 Runtime 的实际版本一致。 */
    private void validateRequest(PlanningRequest request) {
        if (request == null || isBlank(request.tenantId()) || isBlank(request.instanceId())
                || isBlank(request.artifactVersion()) || isBlank(request.artifactDigest())
                || isBlank(request.employeeId()) || isBlank(request.intent())) {
            throw new IllegalArgumentException("Planning request identity and intent are required");
        }
    }

    /** 校验请求、ACTIVE Catalog 与具体 Runtime 注册记录必须绑定到同一发布产物。 */
    private void validateBinding(PlanningRequest request, PlanningCatalog catalog) {
        if (!request.artifactVersion().equals(catalog.artifactVersion())) {
            throw new SecurityException("Planning Catalog does not match Runtime artifact");
        }
        boolean registrationMatched = catalogService.findRegistration(request.tenantId(), request.instanceId(),
                request.artifactDigest()).filter(item -> request.artifactVersion().equals(item.artifactVersion())).isPresent();
        if (!registrationMatched) {
            throw new SecurityException("Runtime registration does not match planning request");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
