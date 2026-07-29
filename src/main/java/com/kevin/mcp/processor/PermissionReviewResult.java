package com.kevin.mcp.processor;

import java.util.List;

/**
 * 承载执行计划权限审查结果，确保 LLM 只输出判断，具体拒绝和脱敏由程序执行。
 *
 * @param decision 审查决策，allow 或 deny
 * @param denyReason 拒绝原因
 * @param matchedDenyRules 命中的禁止规则
 * @param maskInstructions 脱敏指令
 * @param uncertain 是否无法确定
 * @author Kevin
 * @date 2026-07-29
 */
public record PermissionReviewResult(
        String decision,
        String denyReason,
        List<Object> matchedDenyRules,
        List<MaskInstruction> maskInstructions,
        boolean uncertain
) {

    /**
     * 生成直接允许的审查结果。
     *
     * @return 允许结果
     */
    public static PermissionReviewResult allow() {
        return new PermissionReviewResult("allow", "", List.of(), List.of(), false);
    }

    /**
     * 描述单个字段脱敏动作。
     *
     * @param methodKey 内部 Tool 方法唯一键
     * @param fieldPath 字段路径
     * @param maskTemplate 脱敏模板
     * @param ruleId 规则 ID
     * @param ruleName 规则名称
     */
    public record MaskInstruction(
            String methodKey,
            String fieldPath,
            String maskTemplate,
            String ruleId,
            String ruleName
    ) {
    }
}
