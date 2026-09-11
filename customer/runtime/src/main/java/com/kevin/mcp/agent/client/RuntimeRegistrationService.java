package com.kevin.mcp.agent.client;

import com.kevin.mcp.agent.configuration.PlanningGatewayProperties;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.kevin.mcp.util.GsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在客户 Runtime 启动完成后向 Control Plane 登记实际能力版本。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Service
public class RuntimeRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRegistrationService.class);

    private final PlanningGatewayProperties gatewayProperties;
    private final PlanningGatewayClient gatewayClient;
    private final RuntimeIdentityProperties identityProperties;
    private final PrivateMcpToolSchemaRegistry registry;

    /**
     * 注入注册所需的网关、身份和能力清单组件。
     *
     * @param gatewayProperties 网关配置
     * @param gatewayClient 网关客户端
     * @param identityProperties Runtime 身份
     * @param registry 本地能力注册表
     */
    public RuntimeRegistrationService(PlanningGatewayProperties gatewayProperties,
                                       PlanningGatewayClient gatewayClient,
                                       RuntimeIdentityProperties identityProperties,
                                       PrivateMcpToolSchemaRegistry registry) {
        this.gatewayProperties = gatewayProperties;
        this.gatewayClient = gatewayClient;
        this.identityProperties = identityProperties;
        this.registry = registry;
    }

    /**
     * Runtime 启动后自动登记当前实例；注册地址由规划网关地址按固定 API 约定生成，网络失败记录为未注册状态。
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register(ApplicationReadyEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", identityProperties.getTenantId());
        payload.put("instanceId", identityProperties.getInstanceId());
        payload.put("artifactVersion", identityProperties.getArtifactVersion());
        payload.put("artifactDigest", identityProperties.getArtifactDigest());
        payload.put("catalogJson", GsonUtil.toJson(registry.getCloudPlanningSchemas()));
        try {
            gatewayClient.registerRuntime(payload);
            log.info("Runtime registration submitted: tenantId={}, instanceId={}, artifactVersion={}, artifactDigest={}",
                    identityProperties.getTenantId(), identityProperties.getInstanceId(), identityProperties.getArtifactVersion(),
                    identityProperties.getArtifactDigest());
        } catch (RuntimeException exception) {
            log.error("Runtime registration failed; planning remains unavailable: tenantId={}, instanceId={}, "
                            + "artifactVersion={}, artifactDigest={}, reason={}",
                    identityProperties.getTenantId(), identityProperties.getInstanceId(),
                    identityProperties.getArtifactVersion(), identityProperties.getArtifactDigest(),
                    exception.getMessage(), exception);
        }
    }

}
