package com.kevin.mcp.upgrade;

import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.security.TenantMcpAuthentication;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证在线升级入口复用 Runtime 唯一通信 Token 并在执行命令前完成认证。
 *
 * @author Kevin
 * @date 2026-10-27
 */
class McpUpgradeControllerTest {

    /** 使用派生脚本固定输入生成的测试 Runtime Token。 */
    private static final String RUNTIME_TOKEN = "pU6R-g2nq60p5LnBV5ZmNarwOpncqcfBDdBxUWu302E";

    /** 验证错误通信 Token 无法进入升级参数处理。 */
    @Test
    void shouldRejectWrongUnifiedRuntimeToken() {
        McpUpgradeController controller = controller();

        assertThatThrownBy(() -> controller.upgrade("wrong-token", null))
                .isInstanceOf(SecurityException.class);
    }

    /** 验证合法通信 Token 通过认证后继续执行升级参数校验。 */
    @Test
    void shouldUseUnifiedRuntimeTokenForUpgradeAuthentication() {
        McpUpgradeController controller = controller();

        assertThatThrownBy(() -> controller.upgrade(RUNTIME_TOKEN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("升级参数不完整");
    }

    private McpUpgradeController controller() {
        RuntimeIdentityProperties identity = new RuntimeIdentityProperties();
        identity.setInstanceId("runtime-a");
        return new McpUpgradeController(new TenantMcpAuthentication(RUNTIME_TOKEN), "unused", identity);
    }
}
