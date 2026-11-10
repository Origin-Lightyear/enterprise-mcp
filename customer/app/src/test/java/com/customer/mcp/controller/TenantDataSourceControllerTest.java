package com.customer.mcp.controller;

import com.customer.mcp.k3.client.K3Properties;
import com.customer.mcp.wren.WrenProperties;
import com.customer.mcp.wren.WrenSidecarClient;
import com.kevin.mcp.execution.SemanticModelSnapshot;
import com.kevin.mcp.security.TenantMcpAuthentication;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 Tenant 读取 Runtime 数据源目录时的服务认证和类型描述。
 *
 * @author Kevin
 * @date 2026-10-26
 */
class TenantDataSourceControllerTest {

    /** 使用派生脚本固定输入生成的测试 Runtime Token。 */
    private static final String RUNTIME_TOKEN = "pU6R-g2nq60p5LnBV5ZmNarwOpncqcfBDdBxUWu302E";

    /** 验证合法服务 Token 返回 Wren 数据库和金蝶 API 数据源。 */
    @Test
    void shouldListConfiguredDatabaseAndApiSources() {
        WrenProperties wren = new WrenProperties();
        wren.setEnabled(true);
        WrenSidecarClient sidecar = mock(WrenSidecarClient.class);
        when(sidecar.current()).thenReturn(Optional.of(new SemanticModelSnapshot("mdl-1",
                Map.of("sources", Map.of("sales", Map.of())))));
        when(sidecar.datasourceDisplayNames()).thenReturn(Map.of("sales", "销售数据库"));
        K3Properties k3 = new K3Properties();
        k3.setEnabled(true);
        k3.setSourceId("kingdee-k3");
        k3.setDisplayName("金蝶 K3");
        TenantDataSourceController controller = new TenantDataSourceController(
                new TenantMcpAuthentication(RUNTIME_TOKEN), wren, sidecar, k3);

        var sources = controller.list(RUNTIME_TOKEN);

        assertThat(sources).extracting(TenantDataSourceController.DataSourceDescriptor::sourceId)
                .containsExactlyInAnyOrder("sales", "kingdee-k3");
        assertThat(sources).filteredOn(source -> "sales".equals(source.sourceId())).singleElement()
                .satisfies(source -> {
                    assertThat(source.displayName()).isEqualTo("销售数据库");
                    assertThat(source.sourceType()).isEqualTo("DATABASE");
                });
        assertThat(sources).filteredOn(source -> "kingdee-k3".equals(source.sourceId())).singleElement()
                .satisfies(source -> assertThat(source.supportedCredentialTypes())
                        .containsExactly("UNIQUE_IDENTIFIER"));
    }

    /** 验证错误服务 Token 在读取任何数据源前失败关闭。 */
    @Test
    void shouldRejectInvalidServiceToken() {
        TenantDataSourceController controller = new TenantDataSourceController(
                new TenantMcpAuthentication(RUNTIME_TOKEN), new WrenProperties(),
                mock(WrenSidecarClient.class), new K3Properties());

        assertThatThrownBy(() -> controller.list("wrong-secret"))
                .isInstanceOf(SecurityException.class);
    }
}
