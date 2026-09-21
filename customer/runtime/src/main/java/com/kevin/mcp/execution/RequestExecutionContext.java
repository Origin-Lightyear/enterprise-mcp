package com.kevin.mcp.execution;

import com.kevin.mcp.agent.model.EmployeeLlmInvocationConfig;
import com.kevin.mcp.authorization.PermissionReviewResult;

/**
 * 绑定一次请求执行期间不可变的员工配置、权限决策和 MDL 版本。
 *
 * @param llmConfig 员工请求级 LLM 配置
 * @param permissionReview 权限审查结果
 * @param mdlDigest 本次请求绑定的 MDL 摘要
 * @author Kevin
 * @date 2026-09-17
 */
public record RequestExecutionContext(
        EmployeeLlmInvocationConfig llmConfig,
        PermissionReviewResult permissionReview,
        String mdlDigest
) {
}
