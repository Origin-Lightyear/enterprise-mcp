package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.EmployeeAuth;
import com.kevin.mcp.agent.connector.entity.TenantConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通过 HTTP 调用 Platform 与 Tenant 后端，拉取租户配置、员工鉴权和审计结果上报接口。
 *
 * @author Kevin
 * @date 2026-07-29
 */
@Component
public class HttpAgentServerClient {
    private final RestClient saasRestClient;
    private final RestClient tenantRestClient;

    /**
     * 使用 Platform 和 Tenant 服务地址初始化 HTTP 客户端。
     *
     * @param saasServerUrl Platform 后端地址
     * @param tenantServerUrl Tenant 后端地址
     * @param connectTimeout 连接超时
     * @param readTimeout 读取超时
     */
    public HttpAgentServerClient(
            @Value("${saas.saas-server}") String saasServerUrl,
            @Value("${saas.tenant-server}") String tenantServerUrl,
            @Value("${saas.http.connect-timeout:3s}") Duration connectTimeout,
            @Value("${saas.http.read-timeout:10s}") Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.saasRestClient = RestClient.builder()
                .baseUrl(saasServerUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.tenantRestClient = RestClient.builder()
                .baseUrl(tenantServerUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 拉取指定租户的最新配置快照，严格对齐 Platform 的 getConfig 返回体。
     *
     * @param tenantId 租户标识
     * @return 租户配置
     */
    public TenantConfig fetchTenantConfig(String tenantId) {
        Map<?, ?> data = this.readApiResult(this.saasRestClient.get()
                .uri("/inner/tenant/config/{id}", tenantId)
                .retrieve()
                .body(Map.class), "tenant config");
        return new TenantConfig(
                this.longValue(data.get("id")),
                this.stringValue(data.get("llmKey")),
                this.stringValue(data.get("llmUrl")),
                this.integerValue(data.get("status")),
                this.localDateTimeValue(data.get("authEndTime")),
                this.integerValue(data.get("version"))
        );
    }

    /**
     * 拉取指定租户员工的鉴权快照。
     *
     * @param tenantId 租户标识
     * @param employeeId 员工标识
     * @return 员工鉴权信息
     */
    public EmployeeAuth fetchTenantAuth(String tenantId, String employeeId) {
        return this.tenantRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/tenant/auth")
                        .queryParam("tenantId", tenantId)
                        .queryParam("employeeId", employeeId)
                        .build())
                .retrieve()
                .body(EmployeeAuth.class);
    }

    /**
     * 上报 MCP 权限审计结果，Tenant 后端负责落库存档。
     *
     * @param auditPayload 审计载荷
     */
    public void reportPermissionAudit(Map<String, Object> auditPayload) {
        this.tenantRestClient.post()
                .uri("/tenant/permission-audit")
                .body(auditPayload)
                .retrieve()
                .toBodilessEntity();
    }

    private Map<?, ?> readApiResult(Map<?, ?> root, String actionName) {
        if (root == null || !"0".equals(String.valueOf(root.get("code")))) {
            throw new IllegalStateException("Failed to read " + actionName + " from platform service");
        }
        Object rawData = root.get("data");
        if (!(rawData instanceof Map<?, ?> data)) {
            throw new IllegalStateException("Platform service returned invalid " + actionName + " payload");
        }
        return data;
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDateTime localDateTimeValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(text);
    }
}
