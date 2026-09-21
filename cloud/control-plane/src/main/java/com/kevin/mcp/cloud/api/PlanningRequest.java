package com.kevin.mcp.cloud.api;

/**
 * 承载 Runtime 请求云端生成执行计划的参数。
 *
 * @author Kevin
 * @date 2026-09-09
 *
 * @param tenantId 租户唯一编号
 * @param instanceId Runtime 实例编号
 * @param artifactVersion 客户 JAR 版本
 * @param artifactDigest 客户 JAR 摘要
 * @param catalogDigest Runtime 当前精确 Catalog Digest
 * @param employeeId 当前员工编号
 * @param intent 用户意图
 */
public record PlanningRequest(
        String tenantId,
        String instanceId,
        String artifactVersion,
        String artifactDigest,
        String catalogDigest,
        String employeeId,
        String intent
) {
}
