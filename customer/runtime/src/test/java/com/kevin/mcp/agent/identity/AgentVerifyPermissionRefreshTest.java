package com.kevin.mcp.agent.identity;

import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.agent.model.EmployeeAuth;
import com.kevin.mcp.agent.model.TenantConfig;
import com.kevin.mcp.agent.service.TenantCacheService;
import com.kevin.mcp.agent.service.TenantConfigSynchronizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证租户版本不变时撤权和员工停用仍阻止后续新请求，读取失败不能回退旧缓存。
 *
 * @author Kevin
 * @date 2026-10-29
 */
class AgentVerifyPermissionRefreshTest {
    /** 验证每次请求读取当前权限，缓存中旧的允许结果不能继续生效。 */
    @Test
    void everyRequestRefreshesPermissionsAndDisabledEmployeeFailsClosed() {
        AgentSecurityCodec codec = mock(AgentSecurityCodec.class);
        HttpAgentServerClient client = mock(HttpAgentServerClient.class);
        TenantConfigSynchronizer synchronizer = mock(TenantConfigSynchronizer.class);
        RuntimeIdentityProperties properties = new RuntimeIdentityProperties();
        properties.setTenantId("1"); properties.setInstanceId("runtime-1");
        TenantCacheService cache = new TenantCacheService(300, "Asia/Shanghai");
        cache.refreshTenantConfig("1", new TenantConfig(1L, 1, null, 7));
        cache.refreshEmployeeAuth("1", new EmployeeAuth(100L, "ENABLED", 7L, Map.of(), Map.of("old", true)));
        when(codec.isTimestampAllowed(anyLong(), anyLong())).thenReturn(true);
        when(codec.isCredentialTimeAllowed(anyLong(), anyLong(), anyLong())).thenReturn(true);
        when(codec.parseTenantHeader("tenant")).thenReturn(new TenantRequestInfo("1", 100L, 7L, "runtime-1"));
        when(codec.verifySignature(anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        EmployeeAuth revoked = new EmployeeAuth(100L, "ENABLED", 7L, Map.of(), Map.of("queryAllowed", false));
        when(client.fetchTenantAuth("1", 100L)).thenReturn(revoked, new EmployeeAuth(100L, "DISABLED", 7L, Map.of(), Map.of()))
                .thenThrow(new org.springframework.web.client.RestClientException("unavailable"));
        AgentVerify verify = new AgentVerify(codec, cache, client, synchronizer, properties, 300, 60);
        VerifiedEmployee first = verify.verifySourceAndGetEmployee(context("nonce-1"));
        assertThat(first.employeeAuth().permissionConfig()).containsEntry("queryAllowed", false).doesNotContainKey("old");
        assertThat(verify.verifySourceAndGetEmployee(context("nonce-2"))).isNull();
        assertThat(verify.verifySourceAndGetEmployee(context("nonce-3"))).isNull();
        verify(client, times(3)).fetchTenantAuth("1", 100L);
    }

    private McpSyncRequestContext context(String nonce) {
        McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        McpTransportContext transport = mock(McpTransportContext.class);
        Map<String, String> headers = Map.of("authorization", "signature", "tenant", "tenant", "mcpInstanceId", "runtime-1",
                "timestamp", "100", "expiresAt", "160", "nonce", nonce);
        when(transport.get(anyString())).thenAnswer(invocation -> headers.get(invocation.getArgument(0)));
        when(context.transportContext()).thenReturn(transport);
        return context;
    }
}
