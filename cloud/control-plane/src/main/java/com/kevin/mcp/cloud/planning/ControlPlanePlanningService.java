package com.kevin.mcp.cloud.planning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kevin.mcp.cloud.api.PlanningRequest;
import com.kevin.mcp.cloud.catalog.PlanningCatalog;
import com.kevin.mcp.cloud.catalog.PlanningCatalogService;
import com.kevin.mcp.cloud.catalog.PlanningKnowledgeService;
import com.kevin.mcp.cloud.configuration.ControlPlanePlanningProperties;
import com.kevin.mcp.cloud.employee.EmployeeLlmConfigService;
import com.kevin.mcp.cloud.employee.EmployeeLlmInvocationConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 根据 Runtime 明确指定的 Catalog Snapshot 调用模型并生成执行计划。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Service
@Profile("control-plane")
public class ControlPlanePlanningService {

    private static final int PLAN_CONTRACT_VERSION = 1;

    private final PlanningCatalogService catalogService;
    private final PlanningKnowledgeService knowledgeService;
    private final ControlPlanePlanningProperties properties;
    private final ExecutionPlanGenerator planGenerator;
    private final EmployeeLlmConfigService employeeLlmConfigService;

    /**
     * 注入目录、模型和签名配置。
     *
     * @param catalogService 规划目录服务
     * @param knowledgeService 客户本地知识快照查询
     * @param properties Control Plane 配置
     * @param planGenerator 云端执行计划生成器
     * @param employeeLlmConfigService 员工 LLM 配置服务
     */
    public ControlPlanePlanningService(PlanningCatalogService catalogService,
                                       PlanningKnowledgeService knowledgeService,
                                       ControlPlanePlanningProperties properties,
                                       ExecutionPlanGenerator planGenerator,
                                       EmployeeLlmConfigService employeeLlmConfigService) {
        this.catalogService = catalogService;
        this.knowledgeService = knowledgeService;
        this.properties = properties;
        this.planGenerator = planGenerator;
        this.employeeLlmConfigService = employeeLlmConfigService;
    }

    /**
     * 根据精确 Catalog Digest 生成绑定当前 Runtime 的执行计划。
     *
     * @param request 规划请求
     * @return 执行计划
     * @throws Exception 目录或模型调用失败
     */
    public Map<String, Object> plan(PlanningRequest request) throws Exception {
        validateRequest(request);
        PlanningCatalog catalog = catalogService.find(request.tenantId(), request.catalogDigest())
                .orElseThrow(() -> new IllegalStateException("Planning Catalog Snapshot not found"));
        validateBinding(request, catalog);
        EmployeeLlmInvocationConfig invocationConfig = employeeLlmConfigService.getConfig(
                request.tenantId(), request.employeeId());
        String knowledgeJson = request.knowledgeDigest() == null || request.knowledgeDigest().isBlank()
                ? "" : knowledgeService.require(request.tenantId(), request.instanceId(),
                request.artifactDigest(), request.catalogDigest(), request.knowledgeDigest());
        Map<String, Object> modelPlan = planGenerator.generate(
                request.intent(), catalog.catalogJson(), knowledgeJson, invocationConfig);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("planId", java.util.UUID.randomUUID().toString());
        envelope.put("planContractVersion", PLAN_CONTRACT_VERSION);
        envelope.put("instanceId", request.instanceId());
        envelope.put("runtimeVersion", request.artifactVersion());
        envelope.put("artifactDigest", request.artifactDigest());
        envelope.put("catalogDigest", request.catalogDigest());
        envelope.put("mdlDigest", mdlDigest(catalog.catalogJson()));
        envelope.put("asOf", java.time.Instant.now().toString());
        envelope.put("consistencyMode", "BEST_EFFORT_CURRENT");
        envelope.put("tenantId", request.tenantId());
        envelope.put("employeeId", request.employeeId());
        envelope.putAll(modelPlan);
        return envelope;
    }

    /** 校验请求身份必须与已登记 Runtime 的实际版本一致。 */
    private void validateRequest(PlanningRequest request) {
        if (request == null || isBlank(request.tenantId()) || isBlank(request.instanceId())
                || isBlank(request.artifactVersion()) || isBlank(request.artifactDigest())
                || isBlank(request.catalogDigest())
                || request.employeeId() == null || request.employeeId() <= 0 || isBlank(request.intent())) {
            throw new IllegalArgumentException("Planning request identity and intent are required");
        }
    }

    /** 校验请求、Catalog Snapshot 与 Runtime 当前引用必须精确一致。 */
    private void validateBinding(PlanningRequest request, PlanningCatalog catalog) {
        if (!request.catalogDigest().equals(catalog.catalogDigest())) {
            throw new SecurityException("Planning Catalog does not match requested digest");
        }
        boolean registrationMatched = catalogService.findRegistration(request.tenantId(), request.instanceId(),
                request.artifactDigest(), request.catalogDigest())
                .filter(item -> request.artifactVersion().equals(item.artifactVersion())).isPresent();
        if (!registrationMatched) {
            throw new SecurityException("Runtime registration does not match planning request");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String mdlDigest(String catalogJson) {
        JsonObject catalog = JsonParser.parseString(catalogJson).getAsJsonObject();
        return catalog.has("mdlDigest") ? catalog.get("mdlDigest").getAsString() : "";
    }
}
