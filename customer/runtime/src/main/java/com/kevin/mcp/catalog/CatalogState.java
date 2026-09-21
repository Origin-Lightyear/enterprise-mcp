package com.kevin.mcp.catalog;

import java.time.Instant;

/**
 * 承载 Runtime 当前可供管理和运维展示的 Catalog 状态。
 *
 * @param readiness 规划就绪状态
 * @param catalogDigest 当前 Catalog Digest；尚未构建时为空
 * @param mdlDigest 当前 MDL Digest；未配置 Wren 时为空
 * @param reasonCode 不可就绪的稳定原因码
 * @param checkedAt 状态确认时间
 * @author Kevin
 * @date 2026-09-18
 */
public record CatalogState(
        PlanningReadinessStatus readiness,
        String catalogDigest,
        String mdlDigest,
        String reasonCode,
        Instant checkedAt
) {
}
