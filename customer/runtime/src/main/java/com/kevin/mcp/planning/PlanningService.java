package com.kevin.mcp.planning;

import com.kevin.mcp.agent.client.PlanningGatewayClient;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 请求云端生成执行计划；Runtime 的版本和摘要绑定由云端发布目录校验。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Service
public class PlanningService {

    private final PlanningGatewayClient planningGatewayClient;
    private final RuntimeIdentityProperties runtimeIdentityProperties;

    /**
     * 注入云端规划客户端和 Runtime 身份。
     *
     * @param planningGatewayClient 云端规划客户端
     * @param runtimeIdentityProperties Runtime 身份配置
     */
    public PlanningService(PlanningGatewayClient planningGatewayClient,
                           RuntimeIdentityProperties runtimeIdentityProperties) {
        this.planningGatewayClient = planningGatewayClient;
        this.runtimeIdentityProperties = runtimeIdentityProperties;
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
    public String plan(String intent, String tenantId, String employeeId) throws Exception {
        if (runtimeIdentityProperties.getArtifactDigest() == null
                || runtimeIdentityProperties.getArtifactDigest().isBlank()) {
            throw new IllegalStateException("Runtime artifact digest is not configured");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tenantId", tenantId);
        request.put("instanceId", runtimeIdentityProperties.getInstanceId());
        request.put("artifactVersion", runtimeIdentityProperties.getArtifactVersion());
        request.put("artifactDigest", runtimeIdentityProperties.getArtifactDigest());
        request.put("employeeId", employeeId);
        request.put("intent", intent);
        return planningGatewayClient.requestPlan(request);
    }
}
