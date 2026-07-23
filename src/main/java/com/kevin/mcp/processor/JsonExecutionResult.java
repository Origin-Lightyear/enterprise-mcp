package com.kevin.mcp.processor;

import java.util.List;
import java.util.Map;

/**
 * 汇总一次 JSON 协议执行结果。
 * 除最终业务结果外，额外保留步骤结果与执行轨迹，方便排查模型规划与运行时行为是否一致。
 *
 * @param planId 计划唯一标识
 * @param intent 原始用户意图
 * @param success 是否执行成功
 * @param finalResult 最后一步结果
 * @param stepResults 每一步的执行结果
 * @param executedSteps 实际执行过的步骤 ID
 */
public record JsonExecutionResult(
        String planId,
        String intent,
        boolean success,
        Object finalResult,
        Map<String, Object> stepResults,
        List<String> executedSteps
) {
}
