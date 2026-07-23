package com.kevin.mcp.agent.connector.entity;

import java.util.Map;

/**
 * 承载 AgentServer 返回的租户配置快照。
 *
 * @param status 租户当前状态
 * @param authorizationDeadline 授权截止时间，使用 ISO-8601 字符串表示
 * @param versionNumber 当前配置版本号
 * @param modelConfig 模型相关配置
 */
public record TenantConfig(
        String status,
        String authorizationDeadline,
        long versionNumber,
        Map<String, Object> modelConfig) {
}
