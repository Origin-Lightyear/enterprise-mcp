package com.kevin.mcp.authorization;

import java.util.List;

/**
 * 承载员工 LLM 对完整执行计划的一次权限审查结果。
 *
 * @param decision 审查决策，allow 或 deny
 * @param userMessage 可安全返回员工的简短原因
 * @param internalReason 仅供本地审计的判断摘要
 * @param matchedRuleIds 命中的 Tenant 权限规则 ID
 * @param maskInstructions 最终结果脱敏指令
 * @param uncertain 是否无法完成可靠判断
 * @author Kevin
 * @date 2026-09-17
 */
public record PermissionReviewResult(
        String decision,
        String userMessage,
        String internalReason,
        List<String> matchedRuleIds,
        List<MaskInstruction> maskInstructions,
        boolean uncertain
) {

    /**
     * 归一化集合字段，避免权限执行阶段重复判空。
     *
     * @param decision 审查决策
     * @param userMessage 员工提示
     * @param internalReason 审计摘要
     * @param matchedRuleIds 命中规则编号
     * @param maskInstructions 脱敏指令
     * @param uncertain 是否不确定
     */
    public PermissionReviewResult {
        matchedRuleIds = matchedRuleIds == null ? List.of() : List.copyOf(matchedRuleIds);
        maskInstructions = maskInstructions == null ? List.of() : List.copyOf(maskInstructions);
    }

    /**
     * 生成无权限规则时的直接允许结果。
     *
     * @return 允许结果
     */
    public static PermissionReviewResult allow() {
        return new PermissionReviewResult("allow", "", "当前员工没有有效限制规则", List.of(), List.of(), false);
    }

    /**
     * 生成权限审查未能可靠完成时的失败关闭结果。
     *
     * @param internalReason 内部失败摘要
     * @return 拒绝结果
     */
    public static PermissionReviewResult uncertain(String internalReason) {
        return new PermissionReviewResult("deny", "权限校验未完成，请稍后重试",
                internalReason, List.of(), List.of(), true);
    }

    /**
     * 描述一个最终结果脱敏动作。
     *
     * @param capabilityId 产生敏感结果的能力编号
     * @param fieldPaths 固定 Schema API 的结果字段路径
     * @param sourceFields Wren MDL 的规范源字段，例如 Employee.mobile
     * @param maskTemplate 脱敏模板
     * @param ruleId 权限规则 ID
     * @param ruleName 权限规则名称
     */
    public record MaskInstruction(
            String capabilityId,
            List<String> fieldPaths,
            List<String> sourceFields,
            String maskTemplate,
            String ruleId,
            String ruleName
    ) {

        /**
         * 归一化字段集合，保证脱敏阶段只处理不可变快照。
         *
         * @param capabilityId 能力编号
         * @param fieldPaths API 字段路径
         * @param sourceFields Wren 源字段
         * @param maskTemplate 脱敏模板
         * @param ruleId 规则编号
         * @param ruleName 规则名称
         */
        public MaskInstruction {
            fieldPaths = fieldPaths == null ? List.of() : List.copyOf(fieldPaths);
            sourceFields = sourceFields == null ? List.of() : List.copyOf(sourceFields);
        }
    }
}
