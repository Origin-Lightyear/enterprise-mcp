package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.TenantConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 Platform 租户配置轮询的变更处理与失败降级行为。
 *
 * @author Kevin
 * @date 2026-08-01
 */
class TenantConfigSynchronizerTest {

    private HttpAgentServerClient agentServerClient;
    private TenantCacheService tenantCacheService;
    private TenantConfigSynchronizer synchronizer;

    /**
     * 初始化同步器及其隔离依赖。
     */
    @BeforeEach
    void setUp() {
        this.agentServerClient = mock(HttpAgentServerClient.class);
        this.tenantCacheService = new TenantCacheService(300L, "Asia/Shanghai");
        this.synchronizer = new TenantConfigSynchronizer(this.agentServerClient, this.tenantCacheService, "10000");
    }

    /**
     * 验证首次获取到可用配置时缓存租户状态。
     */
    @Test
    void shouldApplyInitialAvailableTenantConfig() {
        TenantConfig config = this.availableConfig(1);
        when(this.agentServerClient.fetchTenantConfig("10000")).thenReturn(config);

        assertThat(this.synchronizer.synchronizeSafely()).isTrue();

        assertThat(this.tenantCacheService.getTenantConfig("10000")).isEqualTo(config);
    }

    /**
     * 验证内容未变化的租户配置仍可安全重复同步。
     */
    @Test
    void shouldKeepConfigWhenRemoteValueDidNotChange() {
        TenantConfig config = this.availableConfig(1);
        this.tenantCacheService.refreshTenantConfig("10000", config);
        when(this.agentServerClient.fetchTenantConfig("10000")).thenReturn(config);

        assertThat(this.synchronizer.synchronizeSafely()).isTrue();

        assertThat(this.tenantCacheService.getTenantConfig("10000")).isEqualTo(config);
    }

    /**
     * 验证租户版本变化后刷新本地状态快照。
     */
    @Test
    void shouldRefreshTenantStateWhenVersionChanged() {
        TenantConfig previous = this.availableConfig(1);
        TenantConfig latest = this.availableConfig(2);
        this.tenantCacheService.refreshTenantConfig("10000", previous);
        when(this.agentServerClient.fetchTenantConfig("10000")).thenReturn(latest);

        this.synchronizer.synchronizeSafely();

        assertThat(this.tenantCacheService.getTenantConfig("10000")).isEqualTo(latest);
    }

    /**
     * 验证禁用状态立即落入缓存。
     */
    @Test
    void shouldApplyDisabledTenantState() {
        TenantConfig disabled = new TenantConfig(10000L, 2, LocalDateTime.now().plusDays(1), 2);
        when(this.agentServerClient.fetchTenantConfig("10000")).thenReturn(disabled);

        this.synchronizer.synchronizeSafely();

        assertThat(this.tenantCacheService.getTenantAvailability("10000"))
                .isEqualTo(TenantAvailability.DISABLED);
    }

    /**
     * 验证 Platform 暂时不可用时保留上一次租户配置。
     */
    @Test
    void shouldKeepPreviousConfigWhenPlatformCallFails() {
        TenantConfig previous = this.availableConfig(1);
        this.tenantCacheService.refreshTenantConfig("10000", previous);
        when(this.agentServerClient.fetchTenantConfig("10000"))
                .thenThrow(new IllegalStateException("platform unavailable"));

        assertThat(this.synchronizer.synchronizeSafely()).isFalse();

        assertThat(this.tenantCacheService.getTenantConfig("10000")).isEqualTo(previous);
    }

    private TenantConfig availableConfig(int version) {
        return new TenantConfig(10000L, 1, LocalDateTime.now().plusDays(1), version);
    }
}
