package com.kevin.mcp.cloud.catalog;

/**
 * 保存客户 Runtime 当前实际部署及 Catalog 引用。
 *
 * @author Kevin
 * @date 2026-09-09
 *
 * @param tenantId 客户编号
 * @param instanceId Runtime 实例编号
 * @param artifactVersion 客户 JAR 版本
 * @param artifactDigest 客户 JAR 摘要
 * @param catalogDigest Runtime 当前 Catalog Digest
 */
public record RuntimeRegistration(
        String tenantId,
        String instanceId,
        String artifactVersion,
        String artifactDigest,
        String catalogDigest
) {
}
