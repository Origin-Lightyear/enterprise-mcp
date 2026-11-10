package com.kevin.mcp.authorization;

import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.execution.RequestExecutionContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 根据员工不可变权限快照执行数据库数据源授权。
 *
 * @author Kevin
 * @date 2026-10-26
 */
@Service
public class DataSourceAuthorizationService {

    private final String instanceId;

    /**
     * 绑定当前 Runtime 实例，避免同租户不同 MCP 的同名数据源互相授权。
     *
     * @param runtimeIdentityProperties Runtime 身份配置
     */
    public DataSourceAuthorizationService(RuntimeIdentityProperties runtimeIdentityProperties) {
        this.instanceId = runtimeIdentityProperties.getInstanceId();
    }

    /**
     * 校验当前员工是否允许在指定数据库数据源执行操作。
     *
     * @param context 当前请求执行上下文
     * @param dataSourceId 数据源稳定标识
     * @param operation 数据库操作类型
     * @throws SecurityException 未配置权限、快照非法或操作被禁止
     */
    public void authorize(RequestExecutionContext context, String dataSourceId, DataSourceOperation operation) {
        if (context == null || dataSourceId == null || dataSourceId.isBlank() || operation == null) {
            throw new SecurityException("数据库数据源授权上下文不完整");
        }
        Object rawPermissions = context.permissionConfig().get("databasePermissions");
        if (!(rawPermissions instanceof List<?> permissions)) {
            throw new SecurityException("当前员工没有数据库数据源权限");
        }
        for (Object rawPermission : permissions) {
            if (!(rawPermission instanceof Map<?, ?> permission)
                    || !instanceId.equals(String.valueOf(permission.get("instanceId")))
                    || !dataSourceId.equals(String.valueOf(permission.get("dataSourceId")))) {
                continue;
            }
            if (Boolean.TRUE.equals(permission.get(permissionKey(operation)))) {
                return;
            }
        }
        throw new SecurityException("当前员工无权在该数据库数据源执行" + operation.name() + "操作");
    }

    private String permissionKey(DataSourceOperation operation) {
        return switch (operation) {
            case QUERY -> "queryAllowed";
            case CREATE -> "createAllowed";
            case UPDATE -> "updateAllowed";
            case DELETE -> "deleteAllowed";
        };
    }
}
