package com.kevin.mcp.execution;

import com.kevin.mcp.agent.model.EmployeeLlmInvocationConfig;
import java.util.Map;

/**
 * 绑定一次请求执行期间不可变的员工配置、数据源权限和 MDL 版本。
 *
 * @param llmConfig 员工请求级 LLM 配置
 * @param mdlDigest 本次请求绑定的 MDL 摘要
 * @param tenantId 已验证租户编号
 * @param employeeId 已验证员工编号
 * @param permissionConfig 员工确定性权限快照
 * @author Kevin
 * @date 2026-09-17
 */
public record RequestExecutionContext(
        EmployeeLlmInvocationConfig llmConfig,
        String mdlDigest,
        String tenantId,
        Long employeeId,
        Map<String, Object> permissionConfig
) {

    /**
     * 兼容不访问数据源的既有测试和调用点。
     *
     * @param llmConfig 员工请求级 LLM 配置
     * @param mdlDigest MDL 摘要
     */
    public RequestExecutionContext(EmployeeLlmInvocationConfig llmConfig,
                                   String mdlDigest) {
        this(llmConfig, mdlDigest, "", null, Map.of());
    }

    /**
     * 归一化员工权限快照，避免工具修改请求级授权依据。
     */
    public RequestExecutionContext {
        permissionConfig = permissionConfig == null ? Map.of() : Map.copyOf(permissionConfig);
    }
}
