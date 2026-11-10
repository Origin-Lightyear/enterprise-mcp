package com.kevin.mcp.authorization;

import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.execution.RequestExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证数据库数据源权限按 Runtime、数据源和操作精确匹配。
 *
 * @author Kevin
 * @date 2026-10-26
 */
class DataSourceAuthorizationServiceTest {

    /** 验证完全匹配的查询权限允许执行。 */
    @Test
    void shouldAllowMatchingQueryPermission() {
        DataSourceAuthorizationService service = service("runtime-1");
        RequestExecutionContext context = context(permission("runtime-1", "sales", true, false));

        assertThatCode(() -> service.authorize(context, "sales", DataSourceOperation.QUERY))
                .doesNotThrowAnyException();
    }

    /** 验证同名数据源不能跨 Runtime 继承权限。 */
    @Test
    void shouldDenyPermissionFromAnotherRuntime() {
        DataSourceAuthorizationService service = service("runtime-2");
        RequestExecutionContext context = context(permission("runtime-1", "sales", true, false));

        assertThatThrownBy(() -> service.authorize(context, "sales", DataSourceOperation.QUERY))
                .isInstanceOf(SecurityException.class);
    }

    /** 验证未配置的数据源默认拒绝。 */
    @Test
    void shouldDenyUnknownDataSource() {
        DataSourceAuthorizationService service = service("runtime-1");
        RequestExecutionContext context = context(permission("runtime-1", "finance", true, false));

        assertThatThrownBy(() -> service.authorize(context, "sales", DataSourceOperation.QUERY))
                .isInstanceOf(SecurityException.class);
    }

    /** 验证查询权限不会隐式授予修改权限。 */
    @Test
    void shouldDenyOperationWithoutExplicitPermission() {
        DataSourceAuthorizationService service = service("runtime-1");
        RequestExecutionContext context = context(permission("runtime-1", "sales", true, false));

        assertThatThrownBy(() -> service.authorize(context, "sales", DataSourceOperation.UPDATE))
                .isInstanceOf(SecurityException.class);
    }

    /** 验证查询、新增、修改和删除均按管理员预先配置的权限独立放行。 */
    @Test
    void shouldAllowEveryExplicitlyGrantedDatabaseOperation() {
        DataSourceAuthorizationService service = service("runtime-1");
        RequestExecutionContext context = context(Map.of(
                "instanceId", "runtime-1",
                "dataSourceId", "sales",
                "queryAllowed", true,
                "createAllowed", true,
                "updateAllowed", true,
                "deleteAllowed", true));

        for (DataSourceOperation operation : DataSourceOperation.values()) {
            assertThatCode(() -> service.authorize(context, "sales", operation))
                    .doesNotThrowAnyException();
        }
    }

    private DataSourceAuthorizationService service(String instanceId) {
        RuntimeIdentityProperties properties = new RuntimeIdentityProperties();
        properties.setInstanceId(instanceId);
        return new DataSourceAuthorizationService(properties);
    }

    private RequestExecutionContext context(Map<String, Object> permission) {
        return new RequestExecutionContext(null, "", "tenant-1", 1001L,
                Map.of("databasePermissions", List.of(permission)));
    }

    private Map<String, Object> permission(String instanceId, String dataSourceId,
                                           boolean queryAllowed, boolean updateAllowed) {
        return Map.of(
                "instanceId", instanceId,
                "dataSourceId", dataSourceId,
                "queryAllowed", queryAllowed,
                "createAllowed", false,
                "updateAllowed", updateAllowed,
                "deleteAllowed", false
        );
    }
}
