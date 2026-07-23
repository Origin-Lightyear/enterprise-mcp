package com.kevin.mcp.agent.connector;

/**
 * 承载 AgentServer 请求头中的租户上下文。
 *
 * @param tenantId 租户标识
 * @param employeeId 员工标识
 * @param versionNumber 当前租户数据版本号
 */
public record TenantRequestInfo(String tenantId, String employeeId, long versionNumber) {
}
