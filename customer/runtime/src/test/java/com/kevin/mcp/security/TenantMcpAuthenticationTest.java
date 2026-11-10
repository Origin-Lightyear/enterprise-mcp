package com.kevin.mcp.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Runtime 统一通信 Token 的启动校验和请求认证。
 *
 * @author Kevin
 * @date 2026-10-27
 */
class TenantMcpAuthenticationTest {

    /** 使用派生脚本固定输入生成的测试 Runtime Token。 */
    private static final String RUNTIME_TOKEN = "pU6R-g2nq60p5LnBV5ZmNarwOpncqcfBDdBxUWu302E";

    /** 验证合法 Token 可同时用于读取和校验。 */
    @Test
    void shouldExposeAndVerifyConfiguredRuntimeToken() {
        TenantMcpAuthentication authentication = new TenantMcpAuthentication(RUNTIME_TOKEN);

        assertThat(authentication.runtimeToken()).isEqualTo(RUNTIME_TOKEN);
        authentication.verify(RUNTIME_TOKEN);
    }

    /** 验证错误 Token 和空配置均失败关闭。 */
    @Test
    void shouldRejectWrongOrMissingToken() {
        TenantMcpAuthentication authentication = new TenantMcpAuthentication(RUNTIME_TOKEN);

        assertThatThrownBy(() -> authentication.verify("wrong-token"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> new TenantMcpAuthentication(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TENANT_MCP_TOKEN");
        assertThatThrownBy(() -> new TenantMcpAuthentication("replace-with-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("格式无效");
    }
}
