package com.kevin.mcp.agent.connector;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 拦截 MCP HTTP 入口的未初始化、禁用或到期租户，避免请求进入 MCP 协议和 Tool 处理链。
 *
 * @author Kevin
 * @date 2026-08-01
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantAvailabilityFilter extends OncePerRequestFilter {
    private final TenantCacheService tenantCacheService;
    private final String tenantId;
    private final String mcpEndpoint;

    /**
     * 注入租户状态缓存和当前实例的 MCP endpoint。
     *
     * @param tenantCacheService 租户缓存服务
     * @param tenantId 当前 MCPServer 绑定的租户
     * @param mcpEndpoint MCP HTTP endpoint
     */
    public TenantAvailabilityFilter(
            TenantCacheService tenantCacheService,
            @Value("${saas.tenant-id}") String tenantId,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String mcpEndpoint
    ) {
        this.tenantCacheService = tenantCacheService;
        this.tenantId = tenantId;
        this.mcpEndpoint = mcpEndpoint;
    }

    /**
     * 根据本地最新租户状态决定是否允许请求进入 MCPServer。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain Filter 调用链
     * @throws ServletException Filter 链执行失败
     * @throws IOException 响应写入失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        TenantAvailability availability = this.tenantCacheService.getTenantAvailability(this.tenantId);
        if (TenantAvailability.AVAILABLE == availability) {
            filterChain.doFilter(request, response);
            return;
        }
        int status = TenantAvailability.NOT_INITIALIZED == availability
                ? HttpServletResponse.SC_SERVICE_UNAVAILABLE
                : HttpServletResponse.SC_FORBIDDEN;
        response.sendError(status, "MCP tenant is not available");
    }

    /**
     * 仅保护 MCP endpoint，避免租户状态影响健康检查和其他管理接口。
     *
     * @param request HTTP 请求
     * @return 是否跳过当前 Filter
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !requestPath.equals(this.mcpEndpoint) && !requestPath.startsWith(this.mcpEndpoint + "/");
    }
}
