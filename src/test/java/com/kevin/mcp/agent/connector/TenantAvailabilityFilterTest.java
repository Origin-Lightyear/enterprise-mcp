package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.TenantConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 验证租户状态在 MCP HTTP 入口的拦截行为。
 *
 * @author Kevin
 * @date 2026-08-01
 */
class TenantAvailabilityFilterTest {

    private TenantCacheService tenantCacheService;
    private TenantAvailabilityFilter filter;

    /**
     * 初始化每个用例独立的租户状态与 Filter。
     */
    @BeforeEach
    void setUp() {
        this.tenantCacheService = new TenantCacheService(300L, "Asia/Shanghai");
        this.filter = new TenantAvailabilityFilter(this.tenantCacheService, "10000", "/mcp");
    }

    /**
     * 验证首次配置同步前 MCP 请求返回服务不可用。
     *
     * @throws Exception Filter 执行失败
     */
    @Test
    void shouldReturnServiceUnavailableBeforeInitialization() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        this.filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(filterChain, never()).doFilter(request, response);
    }

    /**
     * 验证禁用租户在进入 MCP 协议处理链前返回禁止访问。
     *
     * @throws Exception Filter 执行失败
     */
    @Test
    void shouldReturnForbiddenForDisabledTenant() throws Exception {
        this.tenantCacheService.refreshTenantConfig("10000",
                new TenantConfig(10000L, "key", "http://llm", 2, LocalDateTime.now().plusDays(1), 2));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        this.filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(request, response);
    }

    /**
     * 验证租户可用时 MCP 请求继续进入协议处理链。
     *
     * @throws Exception Filter 执行失败
     */
    @Test
    void shouldAllowAvailableTenant() throws Exception {
        this.tenantCacheService.refreshTenantConfig("10000",
                new TenantConfig(10000L, "key", "http://llm", 1, LocalDateTime.now().plusDays(1), 1));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        this.filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    /**
     * 验证非 MCP 接口不受租户状态 Filter 影响。
     *
     * @throws Exception Filter 执行失败
     */
    @Test
    void shouldSkipNonMcpEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        this.filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
