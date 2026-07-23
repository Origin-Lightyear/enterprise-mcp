package com.kevin.mcp.processor;

import java.util.List;

/**
 * 承载一次 JSON 协议调用计划。
 * 将模型返回的原始编排结果固化为稳定结构，便于后续解析校验与执行阶段复用。
 *
 * @param planId 计划唯一标识
 * @param intent 原始用户意图
 * @param steps 按顺序声明的执行步骤
 */
public record JsonExecutionPlan(
        String planId,
        String intent,
        List<JsonExecutionStep> steps
) {
}
