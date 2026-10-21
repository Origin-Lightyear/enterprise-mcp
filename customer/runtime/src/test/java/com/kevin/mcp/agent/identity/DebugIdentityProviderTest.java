package com.kevin.mcp.agent.identity;

import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.configuration.DebugBypassProperties;
import com.kevin.mcp.agent.model.EmployeeAuth;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证调试旁路始终复用 Tenant 真实员工且失败关闭。
 *
 * @author Kevin
 * @date 2026-10-15
 */
class DebugIdentityProviderTest {

    /** 禁用开关时不得访问 Tenant，也不要求预先配置员工编号。 */
    @Test
    void shouldKeepNormalSecurityWhenDisabled() {
        HttpAgentServerClient agentServerClient = mock(HttpAgentServerClient.class);
        DebugBypassProperties properties = new DebugBypassProperties();
        DebugIdentityProvider provider = new DebugIdentityProvider(agentServerClient, properties, "tenant-1");

        assertThat(provider.isEnabled()).isFalse();
        provider.validate();
        verifyNoInteractions(agentServerClient);
    }

    /** 开关开启但缺少员工编号时，应用必须启动失败，避免进入无身份调试模式。 */
    @Test
    void shouldRejectEnabledConfigurationWithoutEmployee() {
        DebugBypassProperties properties = new DebugBypassProperties();
        properties.setEnabled(true);
        DebugIdentityProvider provider = new DebugIdentityProvider(
                mock(HttpAgentServerClient.class), properties, "tenant-1");

        assertThatThrownBy(provider::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enterprise.debug.employee-id");
    }

    /** 每次请求都实时拉取 Tenant 快照，只允许已启用员工进入调试链路。 */
    @Test
    void shouldLoadRealEnabledEmployeeOnEveryRequest() {
        HttpAgentServerClient agentServerClient = mock(HttpAgentServerClient.class);
        DebugBypassProperties properties = new DebugBypassProperties();
        properties.setEnabled(true);
        properties.setEmployeeId(1001L);
        DebugIdentityProvider provider = new DebugIdentityProvider(agentServerClient, properties, "tenant-1");
        EmployeeAuth enabled = new EmployeeAuth(1001L, "ENABLED", 7L, Map.of(), Map.of());
        when(agentServerClient.fetchTenantAuth("tenant-1", 1001L)).thenReturn(enabled);

        VerifiedEmployee employee = provider.loadVerifiedEmployee();

        assertThat(employee.tenantId()).isEqualTo("tenant-1");
        assertThat(employee.employeeAuth()).isSameAs(enabled);
    }

    /** Tenant 员工被禁用后必须失败关闭，禁止继续以旧快照调试。 */
    @Test
    void shouldFailClosedWhenRealEmployeeIsDisabled() {
        HttpAgentServerClient agentServerClient = mock(HttpAgentServerClient.class);
        DebugBypassProperties properties = new DebugBypassProperties();
        properties.setEnabled(true);
        properties.setEmployeeId(1001L);
        DebugIdentityProvider provider = new DebugIdentityProvider(agentServerClient, properties, "tenant-1");
        when(agentServerClient.fetchTenantAuth("tenant-1", 1001L)).thenReturn(
                new EmployeeAuth(1001L, "DISABLED", 8L, Map.of(), Map.of()));

        assertThatThrownBy(provider::loadVerifiedEmployee)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not enabled");
    }

    /** Tenant 返回缺少员工编号的快照时视为无效数据并失败关闭。 */
    @Test
    void shouldRejectInvalidEmployeeSnapshot() {
        HttpAgentServerClient agentServerClient = mock(HttpAgentServerClient.class);
        DebugBypassProperties properties = new DebugBypassProperties();
        properties.setEnabled(true);
        properties.setEmployeeId(1001L);
        DebugIdentityProvider provider = new DebugIdentityProvider(agentServerClient, properties, "tenant-1");
        when(agentServerClient.fetchTenantAuth("tenant-1", 1001L)).thenReturn(
                new EmployeeAuth(null, "ENABLED", 9L, Map.of(), Map.of()));

        assertThatThrownBy(provider::loadVerifiedEmployee)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }
}



