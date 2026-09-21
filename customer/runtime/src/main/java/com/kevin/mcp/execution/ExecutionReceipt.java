package com.kevin.mcp.execution;

import java.util.List;

/**
 * 承载可安全返回 Agent 的业务级执行回执。
 *
 * @param planId 计划编号
 * @param status 最终状态
 * @param asOf 计划统一观察时点
 * @param consistencyMode 数据一致性说明
 * @param operations 已执行的业务操作描述
 * @param executedStepCount 已完成顶层步骤数
 * @param startedAt 请求开始时间
 * @param completedAt 请求完成时间
 * @author Kevin
 * @date 2026-09-17
 */
public record ExecutionReceipt(
        String planId,
        String status,
        String asOf,
        String consistencyMode,
        List<String> operations,
        int executedStepCount,
        String startedAt,
        String completedAt
) {

    /**
     * 归一化业务操作描述，避免回执集合被调用方修改。
     *
     * @param planId 计划编号
     * @param status 最终状态
     * @param asOf 观察时点
     * @param consistencyMode 一致性模式
     * @param operations 业务操作描述
     * @param executedStepCount 已执行步骤数
     * @param startedAt 开始时间
     * @param completedAt 完成时间
     */
    public ExecutionReceipt {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
