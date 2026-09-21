package com.kevin.mcp.cloud.catalog;

import java.time.Instant;

/**
 * 返回 Control Plane 独立确认后的 Catalog Snapshot 身份。
 *
 * @param catalogDigest Control Plane 重算的 Catalog Digest
 * @param confirmedAt 确认时间
 * @author Kevin
 * @date 2026-09-18
 */
public record CatalogConfirmation(String catalogDigest, Instant confirmedAt) {
}
