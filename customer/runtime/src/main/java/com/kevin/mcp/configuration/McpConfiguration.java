package com.kevin.mcp.configuration;

import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.kevin.mcp.resource.configuration.ResourceProfileProperties;
import com.kevin.mcp.agent.configuration.PlanningGatewayProperties;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.agent.configuration.AgentSecurityProperties;
import com.kevin.mcp.agent.configuration.DebugBypassProperties;
import com.kevin.mcp.execution.ExecutionLimitsProperties;
import com.kevin.mcp.export.ExportProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 装配 MCP 传输上下文和 Runtime 类型化配置。
 *
 * @author Kevin
 * @date 2026-07-21
 */
@Configuration
@EnableConfigurationProperties({ResourceProfileProperties.class, PlanningGatewayProperties.class,
        RuntimeIdentityProperties.class, AgentSecurityProperties.class,
        DebugBypassProperties.class, ExecutionLimitsProperties.class,
        ExportProperties.class})
public class McpConfiguration {

    /**
     * 提取 Agent 签名请求头并向 MCP Tool 调用上下文传递。
     *
     * <p>仅保存实际存在的 Header，使缺少部分鉴权参数的请求进入统一失败关闭逻辑，而不是因
     * {@link Map#of(Object, Object, Object, Object)} 不接受 {@code null} 产生服务器异常。</p>
     *
     * @return Streamable HTTP MCP 传输提供器
     */
    @Bean
    public WebMvcStreamableServerTransportProvider transport() {
        return WebMvcStreamableServerTransportProvider.builder()
                .contextExtractor(serverRequest -> {
                    String authorization = serverRequest.headers().firstHeader("Authorization");
                    String tenant = serverRequest.headers().firstHeader("Tenant");
                    String mcpInstanceId = serverRequest.headers().firstHeader("Mcp-Instance-Id");
                    String timestamp = serverRequest.headers().firstHeader("Timestamp");
                    String expiresAt = serverRequest.headers().firstHeader("Expires-At");
                    String nonce = serverRequest.headers().firstHeader("Nonce");
                    Map<String, Object> contextValues = new LinkedHashMap<>();
                    putIfPresent(contextValues, "authorization", authorization);
                    putIfPresent(contextValues, "tenant", tenant);
                    putIfPresent(contextValues, "mcpInstanceId", mcpInstanceId);
                    putIfPresent(contextValues, "timestamp", timestamp);
                    putIfPresent(contextValues, "expiresAt", expiresAt);
                    putIfPresent(contextValues, "nonce", nonce);
                    if (contextValues.isEmpty()) {
                        return McpTransportContext.EMPTY;
                    }
                    return McpTransportContext.create(contextValues);
                })
                .build();
    }

    private static void putIfPresent(Map<String, Object> contextValues, String key, String value) {
        if (value != null) {
            contextValues.put(key, value);
        }
    }
}
