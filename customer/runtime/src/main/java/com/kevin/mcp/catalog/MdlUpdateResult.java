package com.kevin.mcp.catalog;

import java.time.Instant;

/**
 * 返回成功应用后的 Current MDL 和 Catalog 绑定。
 *
 * @param binding 当前规划绑定
 * @param changed 内容是否发生变化
 * @param appliedAt 应用完成时间
 * @author Kevin
 * @date 2026-09-18
 */
public record MdlUpdateResult(PlanningBinding binding, boolean changed, Instant appliedAt) {
}
