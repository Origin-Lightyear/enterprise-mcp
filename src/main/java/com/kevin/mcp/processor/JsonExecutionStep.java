package com.kevin.mcp.processor;

import java.util.List;
import java.util.Map;

/**
 * 描述调用计划中的单个执行步骤。
 * 仅保留执行器真正依赖的最小字段，避免模型输出协议和运行时消费协议发生偏移。
 *
 * @param stepId 步骤唯一标识
 * @param methodKey 内部方法唯一键
 * @param parameters 参数集合
 * @param saveResultAs 结果别名
 * @param dependsOn 前置依赖步骤
 */
public record JsonExecutionStep(
        String stepId,
        String methodKey,
        Map<String, Object> parameters,
        String saveResultAs,
        List<String> dependsOn
) {
}
