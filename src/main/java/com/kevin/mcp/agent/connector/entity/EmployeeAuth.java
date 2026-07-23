package com.kevin.mcp.agent.connector.entity;

import java.util.Map;

/**
 * 承载 AgentServer 返回的员工鉴权快照。
 *
 * @param employeeId 员工标识
 * @param status 员工当前状态
 * @param versionNumber 当前鉴权版本号
 * @param departmentInfo 部门信息
 * @param permissionConfig 权限配置
 */
public record EmployeeAuth(
        String employeeId,
        String status,
        long versionNumber,
        Map<String, Object> departmentInfo,
        Map<String, Object> permissionConfig) {
}
