package com.kevin.mcp.cloud.catalog;

/**
 * 承载 Runtime 请求 Control Plane 确认的不可变 Catalog Snapshot。
 *
 * @param tenantId 租户编号
 * @param instanceId Runtime 全局稳定实例编号
 * @param artifactVersion Runtime 制品版本
 * @param artifactDigest Runtime 制品摘要
 * @param catalogDigest Runtime 声明的 Catalog Digest
 * @param catalogJson 完整 Catalog JSON
 * @author Kevin
 * @date 2026-09-18
 */
public record CatalogCandidate(
        String tenantId,
        String instanceId,
        String artifactVersion,
        String artifactDigest,
        String catalogDigest,
        String catalogJson
) {
}
