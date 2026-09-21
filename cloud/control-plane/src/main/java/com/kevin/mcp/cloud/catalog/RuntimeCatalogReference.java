package com.kevin.mcp.cloud.catalog;

/**
 * 承载 Runtime 已在本地提交的当前 Catalog 引用。
 *
 * @param tenantId 租户编号
 * @param instanceId Runtime 全局稳定实例编号
 * @param artifactVersion Runtime 制品版本
 * @param artifactDigest Runtime 制品摘要
 * @param catalogDigest 当前 Catalog Digest
 * @author Kevin
 * @date 2026-09-18
 */
public record RuntimeCatalogReference(
        String tenantId,
        String instanceId,
        String artifactVersion,
        String artifactDigest,
        String catalogDigest
) {
}
