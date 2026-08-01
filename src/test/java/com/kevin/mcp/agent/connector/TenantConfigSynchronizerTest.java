package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.TenantConfig;
import com.kevin.mcp.processor.LLMProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
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
    private LLMProcessor llmProcessor;
    private TenantConfigSynchronizer synchronizer;

    /**
     * 初始化同步器及其隔离依赖。
     */
    @BeforeEach
    void setUp() {
        this.agentServerClient = mock(HttpAgentServerClient.class);
        this.tenantCacheService = new TenantCacheService(300L, "Asia/Shanghai");
        this.llmProcessor = mock(LLMProcessor.class);
        this.synchronizer = new TenantConfigSynchronizer(
                this.agentServerClient, this.tenantCacheService, this.llmProcessor, "10000");
    }

    /**
     * 验证首次获取到可用配置时缓存租户状态并初始化 LLM。
     */
    @Test
    void shouldApplyInitialAvailableTenantConfig() {
        TenantConfig config = this.availableConfig("key-a", 1);
        when(this.agentServerClient.fetchTenantConfig("10000")).thenReturn(config);

        assertThat(this.synchronizer.synchronizeSafely()).isTrue();

        assertThat(this.tenantCacheService.getTenantConfig("10000")).isEqualTo(config);
        verify(this.llmProcessor).refreshTenantConfig(config);
    }

    /**
     * 验证已生效且内容未变化的配置不会重复请求模型列表。
     */
    @Test
    void shouldSkipLlmRefreshWhenConfigDidNotChange() {
        TenantConfig config = this.availableConfig("key-a", 1);
        this.tenantCacheService.refreshTenantConfig("10000", config);
        when(this.agentServerClient.fetchTenantConfig("10000")).thenReturn(config);
        when(this.llmProcessor.isTenantConfigApplied(config)).thenReturn(true);

        this.synchronizer.synchronizeSafely();

        verify(this.llmProcessor, never()).refreshTenantConfig(config);
    }

    /**
     * 验证 LLM 密钥变更后触发模型连接重新验证。
     */
    @Test
    void shouldRefreshLlmWhenCredentialsChanged() {
        TenantConfig previous = this.availableConfig("key-a", 1);
        TenantConfig latest = this.availableConfig("key-b", 2);
        this.tenantCacheService.refreshTenantConfig("10000", previous);
        when(this.agentServerClient.fetchTenantConfig("10000")).thenReturn(latest);

        this.synchronizer.synchronizeSafely();

        verify(this.llmProcessor).refreshTenantConfig(latest);
    }

    /**
     * 验证禁用状态立即落入缓存且不会继续刷新 LLM。
     */
    @Test
    void shouldApplyDisabledStateWithoutRefreshingLlm() {
        TenantConfig disabled = new TenantConfig(10000L, "key-a", "http://llm", 2,
                LocalDateTime.now().plusDays(1), 2);
        when(this.agentServerClient.fetchTenantConfig("10000")).thenReturn(disabled);

        this.synchronizer.synchronizeSafely();

        assertThat(this.tenantCacheService.getTenantAvailability("10000"))
                .isEqualTo(TenantAvailability.DISABLED);
        verify(this.llmProcessor, never()).refreshTenantConfig(disabled);
    }

    /**
     * 验证 Platform 暂时不可用时保留上一次租户配置。
     */
    @Test
    void shouldKeepPreviousConfigWhenPlatformCallFails() {
        TenantConfig previous = this.availableConfig("key-a", 1);
        this.tenantCacheService.refreshTenantConfig("10000", previous);
        reset(this.llmProcessor);
        when(this.agentServerClient.fetchTenantConfig("10000"))
                .thenThrow(new IllegalStateException("platform unavailable"));

        assertThat(this.synchronizer.synchronizeSafely()).isFalse();

        assertThat(this.tenantCacheService.getTenantConfig("10000")).isEqualTo(previous);
        verify(this.llmProcessor, never()).refreshTenantConfig(previous);
    }

    private TenantConfig availableConfig(String llmKey, int version) {
        return new TenantConfig(10000L, llmKey, "http://llm", 1, LocalDateTime.now().plusDays(1), version);
    }
}
