package com.kevin.mcp.configuration;

import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * McpConfiguration
 *
 * @author Kevin
 * 2026/7/21
 */
@Configuration
public class McpConfiguration {

    /**
     * MCPServer 向下传递 Authorization
     *
     * @return McpTransportProvider
     */
    @Bean
    public WebMvcStreamableServerTransportProvider transport() {
        return WebMvcStreamableServerTransportProvider.builder()
                .contextExtractor(serverRequest -> {
                    String authorization = serverRequest.headers().firstHeader("Authorization");
                    String tenant = serverRequest.headers().firstHeader("Tenant");
                    String timestamp = serverRequest.headers().firstHeader("Timestamp");
                    String nonce = serverRequest.headers().firstHeader("Nonce");
                    if (authorization != null || tenant != null || timestamp != null || nonce != null) {
                        return McpTransportContext.create(Map.of(
                                "authorization", authorization,
                                "tenant", tenant,
                                "timestamp", timestamp,
                                "nonce", nonce
                        ));
                    } else {
                        return McpTransportContext.EMPTY;
                    }
                })
                .build();
    }
}
