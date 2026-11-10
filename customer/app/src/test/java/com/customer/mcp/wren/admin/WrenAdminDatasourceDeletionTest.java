package com.customer.mcp.wren.admin;

import com.customer.mcp.wren.WrenProperties;
import com.customer.mcp.wren.WrenSidecarClient;
import com.kevin.mcp.catalog.CatalogException;
import com.kevin.mcp.catalog.MdlUpdateCommand;
import com.kevin.mcp.catalog.MdlUpdateResult;
import com.kevin.mcp.catalog.RuntimeCatalog;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证删除数据源必须经过管理员 Session、CSRF 及 Runtime Catalog 提交流程。
 *
 * @author Kevin
 * @date 2026-10-23
 */
class WrenAdminDatasourceDeletionTest {

    /** 验证更新显示名称后立即重发 Planning Catalog。 */
    @Test
    void shouldSynchronizeCatalogAfterDatasourceUpdate() {
        WrenSidecarClient client = mock(WrenSidecarClient.class);
        RuntimeCatalog catalog = mock(RuntimeCatalog.class);
        WrenAdminController controller = new WrenAdminController(client, catalog);
        WrenAdminController.DatasourceRequest request = new WrenAdminController.DatasourceRequest(
                "sales", "销售数据库", "mysql", "db.internal", "3306", "sales", "app", "", null,
                "ENABLED", null, true);
        Map<String, Object> response = Map.of("saved", true);
        when(client.adminExchange(org.springframework.http.HttpMethod.PUT,
                "/internal/admin/datasources/sales", request)).thenAnswer(invocation -> response);

        assertThat(controller.updateDatasource("sales", request)).isEqualTo(response);

        verify(catalog).synchronize();
    }

    /** 验证快捷改名只调用显示名称接口并同步 Planning Catalog。 */
    @Test
    void shouldUpdateOnlyDatasourceDisplayNameAndSynchronizeCatalog() {
        WrenSidecarClient client = mock(WrenSidecarClient.class);
        RuntimeCatalog catalog = mock(RuntimeCatalog.class);
        WrenAdminController controller = new WrenAdminController(client, catalog);
        WrenAdminController.DisplayNameRequest request = new WrenAdminController.DisplayNameRequest("华东销售库");
        Map<String, Object> response = Map.of("name", "sales", "displayName", "华东销售库");
        when(client.adminExchange(org.springframework.http.HttpMethod.PUT,
                "/internal/admin/datasources/sales/display-name", request)).thenAnswer(invocation -> response);

        assertThat(controller.updateDatasourceDisplayName("sales", request)).isEqualTo(response);

        verify(catalog).synchronize();
    }

    /** 验证本地显示名称已保存时，云端短暂失败不把保存结果误报为失败。 */
    @Test
    void shouldKeepDatasourceUpdateSuccessfulWhenCatalogSynchronizationIsPending() {
        WrenSidecarClient client = mock(WrenSidecarClient.class);
        RuntimeCatalog catalog = mock(RuntimeCatalog.class);
        WrenAdminController controller = new WrenAdminController(client, catalog);
        WrenAdminController.DatasourceRequest request = new WrenAdminController.DatasourceRequest(
                "sales", "销售数据库", "mysql", "db.internal", "3306", "sales", "app", "", null,
                "ENABLED", null, true);
        Map<String, Object> response = Map.of("saved", true);
        when(client.adminExchange(org.springframework.http.HttpMethod.PUT,
                "/internal/admin/datasources/sales", request)).thenAnswer(invocation -> response);
        doThrow(new CatalogException(CatalogException.Code.CONTROL_PLANE_UNAVAILABLE, "offline"))
                .when(catalog).synchronize();

        assertThat(controller.updateDatasource("sales", request)).isEqualTo(response);
    }

    /** 验证无会话及缺少 CSRF 的删除在调用业务模块前被拒绝。 */
    @Test
    void shouldRequireSessionAndCsrfBeforeDatasourceDeletion() throws Exception {
        RuntimeCatalog catalog = mock(RuntimeCatalog.class);
        WrenProperties properties = new WrenProperties();
        properties.setAdminToken("test-admin");
        WrenAdminSessionService sessions = new WrenAdminSessionService(properties);
        WrenAdminSessionService.Session session = sessions.create("test-admin");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WrenAdminController(mock(WrenSidecarClient.class), catalog))
                .addFilters(new WrenAdminAuthenticationFilter(sessions, properties)).build();

        mvc.perform(delete("/internal/wren/admin/datasources/sales")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/internal/wren/admin/datasources/sales")
                .cookie(new Cookie(WrenAdminSessionService.SESSION_COOKIE_NAME, session.id())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalog);

        when(catalog.applyMdl(any())).thenReturn(new MdlUpdateResult(null, true, Instant.now()));
        mvc.perform(delete("/internal/wren/admin/datasources/sales")
                .cookie(new Cookie(WrenAdminSessionService.SESSION_COOKIE_NAME, session.id()))
                .header("X-CSRF-Token", session.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedMdlDigest\":\"old-mdl\",\"idempotencyKey\":\"delete-1\"}"))
                .andExpect(status().isOk());
        verify(catalog).applyMdl(new MdlUpdateCommand("sales", "old-mdl", "delete-1", session.id(), true));
    }
}
