package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.TenantConfig;
import com.kevin.mcp.processor.LLMProcessor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 轮询 Platform 租户配置，并将租户状态和 LLM 连接变更同步到 MCPServer。
 *
 * @author Kevin
 * @date 2026-08-01
 */
@Component
public class TenantConfigSynchronizer {
    private static final Logger log = LoggerFactory.getLogger(TenantConfigSynchronizer.class);

    private final HttpAgentServerClient agentServerClient;
    private final TenantCacheService tenantCacheService;
    private final LLMProcessor llmProcessor;
    private final String tenantId;

    /**
     * 注入租户配置同步依赖。
     *
     * @param agentServerClient Platform 与 Tenant 服务客户端
     * @param tenantCacheService 租户缓存服务
     * @param llmProcessor LLM 处理器
     * @param tenantId 当前 MCPServer 绑定的租户
     */
    public TenantConfigSynchronizer(
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
     * 在服务启动时同步一次配置；失败时保持未初始化状态并等待定时任务重试。
     */
    @PostConstruct
    public void initialize() {
        this.synchronizeSafely();
    }

    /**
     * 按固定间隔拉取 Platform 配置，使租户禁用、到期和配置修改及时在本实例生效。
     */
    @Scheduled(
            initialDelayString = "${saas.tenant-config-polling.initial-delay:30s}",
            fixedDelayString = "${saas.tenant-config-polling.fixed-delay:30s}"
    )
    public void pollTenantConfig() {
        this.synchronizeSafely();
    }

    /**
     * 立即拉取一次租户配置，供请求携带更高版本号时缩短配置生效等待时间。
     *
     * @return 是否成功读取并缓存 Platform 配置
     */
    public synchronized boolean synchronizeSafely() {
        try {
            TenantConfig latestConfig = this.agentServerClient.fetchTenantConfig(this.tenantId);
            this.validateTenant(latestConfig);
            TenantConfig previousConfig = this.tenantCacheService.getTenantConfig(this.tenantId);

            // 状态与有效期必须优先落缓存，确保禁用或到期租户立即在 HTTP 边界被拒绝。
            this.tenantCacheService.refreshTenantConfig(this.tenantId, latestConfig);
            if (this.shouldRefreshLlm(previousConfig, latestConfig)) {
                this.llmProcessor.refreshTenantConfig(latestConfig);
            }
            if (!Objects.equals(previousConfig, latestConfig)) {
                log.info("已同步 Platform 租户配置，tenantId: {}，version: {}，status: {}，authEndTime: {}",
                        this.tenantId, latestConfig.version(), latestConfig.status(), latestConfig.authEndTime());
            }
            return true;
        } catch (RuntimeException exception) {
            log.warn("同步 Platform 租户配置未完全成功，tenantId: {}，保留已验证的 LLM 连接并等待重试: {}",
                    this.tenantId, exception.getMessage());
            return false;
        }
    }

    private boolean shouldRefreshLlm(TenantConfig previousConfig, TenantConfig latestConfig) {
        if (TenantAvailability.AVAILABLE != this.tenantCacheService.getTenantAvailability(this.tenantId)) {
            return false;
        }
        return previousConfig == null
                || !Objects.equals(previousConfig.llmUrl(), latestConfig.llmUrl())
                || !Objects.equals(previousConfig.llmKey(), latestConfig.llmKey())
                || !this.llmProcessor.isTenantConfigApplied(latestConfig);
    }

    private void validateTenant(TenantConfig tenantConfig) {
        if (tenantConfig == null || tenantConfig.id() == null
                || !this.tenantId.equals(String.valueOf(tenantConfig.id()))) {
            throw new IllegalStateException("Platform returned a tenant config that does not match this MCPServer");
        }
    }
}
