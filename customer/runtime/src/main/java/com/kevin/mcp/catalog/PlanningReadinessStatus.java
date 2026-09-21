package com.kevin.mcp.catalog;

/**
 * 表示 Runtime 当前是否能够安全请求和执行云端计划。
 *
 * @author Kevin
 * @date 2026-09-18
 */
public enum PlanningReadinessStatus {

    /** 当前 Catalog 已获 Control Plane 确认并完成引用，可以规划和执行。 */
    READY,

    /** 进程存活，但当前 Catalog 尚未完成云端确认或引用恢复。 */
    NOT_READY
}
