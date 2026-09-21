package com.kevin.mcp.cloud.catalog;

import java.time.Instant;

/**
 * 保存一个租户按内容寻址的不可变 Catalog Snapshot。
 *
 * @author Kevin
 * @date 2026-09-09
 *
 * @param tenantId 客户编号
 * @param catalogDigest 规划目录摘要
 * @param catalogJson 供规划器使用的目录 JSON
 * @param createdAt 创建时间
 */
public record PlanningCatalog(
        String tenantId,
        String catalogDigest,
        String catalogJson,
        Instant createdAt
) {
}
