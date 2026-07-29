package com.kevin.mcp.agent.connector;

import jakarta.annotation.PostConstruct;
import com.kevin.mcp.agent.connector.entity.TenantConfig;
import com.kevin.mcp.processor.LLMProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 在服务启动时预拉取一次租户配置，避免首个请求承担全部同步成本。
 */
@Component
public class AgentStartupSynchronizer {
    private final HttpAgentServerClient agentServerClient;
    private final TenantCacheService tenantCacheService;
    private final LLMProcessor llmProcessor;
    private final String tenantId;

    /**
     * 初始化启动同步器。
     *
     * @param agentServerClient AgentServer 客户端
     * @param tenantCacheService 租户缓存服务
     * @param tenantId 当前服务绑定的租户
     */
    public AgentStartupSynchronizer(
            HttpAgentServerClient agentServerClient,
            TenantCacheService tenantCacheService,
            LLMProcessor llmProcessor,
            @Value("${saas.tenant-id}") String tenantId
    ) {
        this.agentServerClient = agentServerClient;
        this.tenantCacheService = tenantCacheService;
        this.llmProcessor = llmProcessor;
        this.tenantId = tenantId;
    }

    /**
     * 启动完成后立即同步一次租户配置，让基础状态尽早落入本地缓存。
     */
    @PostConstruct
    public void syncTenantConfig() {
        TenantConfig tenantConfig = this.agentServerClient.fetchTenantConfig(this.tenantId);
        this.tenantCacheService.refreshTenantConfig(this.tenantId, tenantConfig);
        this.llmProcessor.refreshTenantConfig(tenantConfig);
    }
}
