package com.customer.mcp.health;

import com.customer.mcp.wren.WrenProperties;
import com.customer.mcp.wren.WrenSidecarClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 Runtime 健康接口对 Wren 状态的聚合规则。
 *
 * @author Kevin
 * @date 2026-09-18
 */
class RuntimeHealthControllerTest {

    /** 验证 Wren 已启用且连通时 Runtime 返回健康。 */
    @Test
    void shouldReportUpWhenWrenIsHealthy() throws Exception {
        WrenProperties properties = enabledWrenProperties();
        WrenSidecarClient client = mock(WrenSidecarClient.class);
        when(client.isHealthy()).thenReturn(true);

        mockMvc(properties, client).perform(get("/internal/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.runtime.status").value("UP"))
                .andExpect(jsonPath("$.components.wren.status").value("UP"))
                .andExpect(jsonPath("$.components.wren.enabled").value(true));
    }

    /** 验证 Wren 已启用但无法连通时 Runtime 返回不可用。 */
    @Test
    void shouldReportDownWhenWrenIsUnreachable() throws Exception {
        WrenProperties properties = enabledWrenProperties();
        WrenSidecarClient client = mock(WrenSidecarClient.class);
        when(client.isHealthy()).thenReturn(false);

        mockMvc(properties, client).perform(get("/internal/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.runtime.status").value("UP"))
                .andExpect(jsonPath("$.components.wren.status").value("DOWN"))
                .andExpect(jsonPath("$.components.wren.enabled").value(true));
    }

    /** 验证显式关闭 Wren 时不发起探测，并清楚标记组件已禁用。 */
    @Test
    void shouldReportUpWithoutProbeWhenWrenIsDisabled() throws Exception {
        WrenProperties properties = new WrenProperties();
        properties.setEnabled(false);
        WrenSidecarClient client = mock(WrenSidecarClient.class);

        mockMvc(properties, client).perform(get("/internal/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.wren.status").value("DISABLED"))
                .andExpect(jsonPath("$.components.wren.enabled").value(false));
        verifyNoInteractions(client);
    }

    private WrenProperties enabledWrenProperties() {
        WrenProperties properties = new WrenProperties();
        properties.setEnabled(true);
        return properties;
    }

    private MockMvc mockMvc(WrenProperties properties, WrenSidecarClient client) {
        return MockMvcBuilders.standaloneSetup(new RuntimeHealthController(properties, client)).build();
    }
}
