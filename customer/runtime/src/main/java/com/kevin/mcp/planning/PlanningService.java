package com.kevin.mcp.planning;

import com.kevin.mcp.agent.client.PlanningGatewayClient;
import com.kevin.mcp.catalog.PlanningBinding;
import com.kevin.mcp.catalog.RuntimeCatalog;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用 Runtime Current Catalog 的精确绑定请求云端生成执行计划。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Service
public class PlanningService {

    private final PlanningGatewayClient planningGatewayClient;
    private final RuntimeCatalog runtimeCatalog;
    private PlanningKnowledgeProvider knowledgeProvider;

    /**
     * 注入云端规划客户端和 Runtime 身份。
     *
     * @param planningGatewayClient 云端规划客户端
     * @param runtimeCatalog Runtime Catalog module
     */
    public PlanningService(PlanningGatewayClient planningGatewayClient,
                           RuntimeCatalog runtimeCatalog) {
        this.planningGatewayClient = planningGatewayClient;
        this.runtimeCatalog = runtimeCatalog;
    }

    /**
     * 接入客户可选的本地知识摘要发布器。
     *
     * @param knowledgeProvider 客户本地知识提供器
     */
    @Autowired(required = false)
    public void setKnowledgeProvider(PlanningKnowledgeProvider knowledgeProvider) {
        this.knowledgeProvider = knowledgeProvider;
    }

    /**
     * 从云端获取执行计划；网关未配置或不可用时失败关闭。
     *
     * @param intent 用户意图
     * @param tenantId 当前员工所属租户编号
     * @param employeeId 当前员工编号
     * @return 执行计划 JSON
     * @throws Exception 规划请求失败
     */
    public String plan(String intent, String tenantId, Long employeeId) throws Exception {
        PlanningBinding binding = runtimeCatalog.requirePlanningBinding();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tenantId", tenantId);
        request.put("instanceId", binding.instanceId());
        request.put("artifactVersion", binding.artifactVersion());
        request.put("artifactDigest", binding.artifactDigest());
        request.put("catalogDigest", binding.catalogDigest());
        request.put("employeeId", employeeId);
        request.put("intent", intent);
        if (knowledgeProvider != null) {
            String knowledgeDigest = knowledgeProvider.currentDigest();
            if (knowledgeDigest != null && !knowledgeDigest.isBlank()) {
                request.put("knowledgeDigest", knowledgeDigest);
            }
        }
        return planningGatewayClient.requestPlan(request);
    }
}
