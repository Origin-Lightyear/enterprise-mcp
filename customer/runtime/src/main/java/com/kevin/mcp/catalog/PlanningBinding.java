package com.kevin.mcp.catalog;

/**
 * 承载 Runtime 发起规划时必须声明的精确当前绑定。
 *
 * @param tenantId 租户编号
 * @param instanceId Runtime 全局稳定实例编号
 * @param artifactVersion Runtime 制品版本
 * @param artifactDigest Runtime 制品摘要
 * @param catalogDigest 当前 Catalog Digest
 * @param mdlDigest 当前 MDL Digest；未配置 Wren 时为空
 * @author Kevin
 * @date 2026-09-18
 */
public record PlanningBinding(
        String tenantId,
        String instanceId,
        String artifactVersion,
        String artifactDigest,
        String catalogDigest,
        String mdlDigest
) {
}
