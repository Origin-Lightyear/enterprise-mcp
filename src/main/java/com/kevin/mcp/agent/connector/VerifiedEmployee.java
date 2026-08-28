package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.EmployeeAuth;

/**
 * 承载已通过签名、租户状态和员工状态校验的请求身份。
 *
 * @param tenantId 已验证的租户标识
 * @param employeeAuth 员工鉴权快照
 * @author Kevin
 * @date 2026-08-10
 */
public record VerifiedEmployee(String tenantId, EmployeeAuth employeeAuth) {
}
