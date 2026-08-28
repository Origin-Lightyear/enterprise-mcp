package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.EmployeeLlmConfig;
import com.kevin.mcp.processor.LLMProcessor;
import com.kevin.mcp.processor.LlmInvocationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证员工 LLM 配置缓存的命中、身份隔离和硬过期行为。
 *
 * @author Kevin
 * @date 2026-08-10
 */
class EmployeeLlmConfigServiceTest {

    private HttpAgentServerClient agentServerClient;
    private LLMProcessor llmProcessor;
    private MutableClock clock;

    /**
     * 初始化远端客户端和 LLM 处理器替身，避免测试依赖外部服务。
     */
    @BeforeEach
    void setUp() {
        this.agentServerClient = mock(HttpAgentServerClient.class);
        this.llmProcessor = mock(LLMProcessor.class);
        this.clock = new MutableClock(Instant.parse("2026-08-10T12:00:00Z"));
    }

    /**
     * 验证相同租户员工在 TTL 内只读取一次 Platform 配置。
     */
    @Test
    void shouldReuseConfigWithinTtl() {
        EmployeeLlmConfig remoteConfig = new EmployeeLlmConfig("key-a", "http://llm-a");
        LlmInvocationConfig invocationConfig = new LlmInvocationConfig("http://llm-a", "key-a", "model-a");
        when(this.agentServerClient.fetchEmployeeLlmConfig("1", "1001")).thenReturn(remoteConfig);
        when(this.llmProcessor.prepareInvocationConfig(remoteConfig)).thenReturn(invocationConfig);
        EmployeeLlmConfigService service = this.newService(Duration.ofMinutes(5));

        assertSame(invocationConfig, service.getConfig("1", "1001"));
        assertSame(invocationConfig, service.getConfig("1", "1001"));

        verify(this.agentServerClient).fetchEmployeeLlmConfig("1", "1001");
        verify(this.llmProcessor).prepareInvocationConfig(remoteConfig);
    }

    /**
     * 验证相同员工编号在不同租户下不会共用缓存密钥。
     */
    @Test
    void shouldIsolateSameEmployeeIdByTenant() {
        EmployeeLlmConfig tenantOneConfig = new EmployeeLlmConfig("key-a", "http://llm-a");
        EmployeeLlmConfig tenantTwoConfig = new EmployeeLlmConfig("key-b", "http://llm-b");
        LlmInvocationConfig tenantOneInvocation = new LlmInvocationConfig("http://llm-a", "key-a", "model-a");
        LlmInvocationConfig tenantTwoInvocation = new LlmInvocationConfig("http://llm-b", "key-b", "model-b");
        when(this.agentServerClient.fetchEmployeeLlmConfig("1", "1001")).thenReturn(tenantOneConfig);
        when(this.agentServerClient.fetchEmployeeLlmConfig("2", "1001")).thenReturn(tenantTwoConfig);
        when(this.llmProcessor.prepareInvocationConfig(tenantOneConfig)).thenReturn(tenantOneInvocation);
        when(this.llmProcessor.prepareInvocationConfig(tenantTwoConfig)).thenReturn(tenantTwoInvocation);
        EmployeeLlmConfigService service = this.newService(Duration.ofMinutes(5));

        LlmInvocationConfig first = service.getConfig("1", "1001");
        LlmInvocationConfig second = service.getConfig("2", "1001");

        assertNotSame(first, second);
        assertSame(tenantOneInvocation, first);
        assertSame(tenantTwoInvocation, second);
    }

    /**
     * 验证缓存硬过期后刷新失败时不回退到可能已撤销的旧密钥。
     *
     */
    @Test
    void shouldFailClosedWhenExpiredConfigCannotRefresh() {
        EmployeeLlmConfig remoteConfig = new EmployeeLlmConfig("key-a", "http://llm-a");
        LlmInvocationConfig invocationConfig = new LlmInvocationConfig("http://llm-a", "key-a", "model-a");
        when(this.agentServerClient.fetchEmployeeLlmConfig("1", "1001"))
                .thenReturn(remoteConfig)
                .thenThrow(new IllegalStateException("platform unavailable"));
        when(this.llmProcessor.prepareInvocationConfig(remoteConfig)).thenReturn(invocationConfig);
        EmployeeLlmConfigService service = this.newService(Duration.ofMillis(10));
        assertSame(invocationConfig, service.getConfig("1", "1001"));

        this.clock.advance(Duration.ofMillis(30));

        assertThrows(IllegalStateException.class, () -> service.getConfig("1", "1001"));
    }

    private EmployeeLlmConfigService newService(Duration ttl) {
        return new EmployeeLlmConfigService(this.agentServerClient, this.llmProcessor, ttl, 100, this.clock);
    }

    /**
     * 提供可手动推进的测试时钟，避免缓存过期测试依赖线程休眠和机器调度。
     *
     * @author Kevin
     * @date 2026-08-10
     */
    private static final class MutableClock extends Clock {

        private Instant currentInstant;

        private MutableClock(Instant currentInstant) {
            this.currentInstant = currentInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(this.currentInstant, zone);
        }

        @Override
        public Instant instant() {
            return this.currentInstant;
        }

        private void advance(Duration duration) {
            this.currentInstant = this.currentInstant.plus(duration);
        }
    }
}
