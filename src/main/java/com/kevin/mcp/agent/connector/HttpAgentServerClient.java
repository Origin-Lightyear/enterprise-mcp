package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.EmployeeAuth;
import com.kevin.mcp.agent.connector.entity.TenantConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 通过 HTTP 调用 AgentServer 拉取租户配置与员工鉴权信息。
 */
@Component
public class HttpAgentServerClient {
    private final RestClient restClient;

    /**
     * 使用 AgentServer 基础地址初始化 HTTP 客户端。
     *
     * @param agentUrl AgentServer 地址
     */
    public HttpAgentServerClient(
            @Value("${agent.url}") String agentUrl
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(agentUrl)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
    /**
     * 拉取指定租户的最新配置快照。
     *
     * @param tenantId 租户标识
     * @return 租户配置
     */
    public TenantConfig fetchTenantConfig(String tenantId) {
        return this.restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/tenant/config").queryParam("tenantId", tenantId).build())
                .retrieve()
                .body(TenantConfig.class);
    }
    /**
     * 拉取指定租户员工的鉴权快照。
     *
     * @param tenantId 租户标识
     * @param employeeId 员工标识
     * @return 员工鉴权信息
     */
    public EmployeeAuth fetchTenantAuth(String tenantId, String employeeId) {
        return this.restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/tenant/auth")
                        .queryParam("tenantId", tenantId)
                        .queryParam("employeeId", employeeId)
                        .build())
                .retrieve()
                .body(EmployeeAuth.class);
    }
}
