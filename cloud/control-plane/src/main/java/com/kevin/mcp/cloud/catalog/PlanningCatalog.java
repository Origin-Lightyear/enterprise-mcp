package com.kevin.mcp.cloud.catalog;

import java.time.Instant;

/**
 * 保存一个租户 JAR 对应的完整能力 Schema 快照。
 *
 * @author Kevin
 * @date 2026-09-09
 *
 * @param tenantId 客户编号
 * @param artifactVersion 客户 JAR 版本
 * @param artifactDigest 客户 JAR 摘要
 * @param catalogDigest 规划目录摘要
 * @param catalogJson 供规划器使用的目录 JSON
 * @param createdAt 创建时间
 */
public record PlanningCatalog(
        String tenantId,
        String artifactVersion,
        String artifactDigest,
        String catalogDigest,
        String catalogJson,
        Instant createdAt
) {
}
