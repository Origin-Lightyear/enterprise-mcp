package com.kevin.mcp.authorization;

import com.kevin.mcp.processor.JsonExecutionPlan;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 使用客户侧确定性规则审查执行计划并处理敏感结果。
 *
 * <p>自然语言规则不能在 Runtime 内再次调用 LLM 解释。尚未转换为明确能力白名单的禁止规则
 * 必须失败关闭；敏感规则尚未提供精确字段路径时对整份结果脱敏，避免不确定状态下泄露数据。</p>
 *
 * @author Kevin
 * @date 2026-09-10
 */
@Service
public class PermissionReviewService {

    private static final String DETERMINISTIC_REVIEW_VERSION = "runtime-deterministic-v1";

    /**
     * 审查当前权限快照，不允许客户 Runtime 通过 LLM 猜测自然语言规则。
     *
     * @param plan 云端返回并解析的执行计划
     * @param permissionConfig 当前员工有效权限快照
     * @return 确定性的允许、拒绝或整份结果脱敏决策
     */
    public PermissionReviewResult review(JsonExecutionPlan plan, Map<String, Object> permissionConfig) {
        if (plan == null) {
            throw new IllegalArgumentException("Execution plan is required");
        }
        List<?> denyRules = listValue(permissionConfig, "denyRules");
        if (!denyRules.isEmpty()) {
            return new PermissionReviewResult(
                    "deny",
                    "当前权限包含尚未转换为能力白名单的禁止规则，Runtime 已按失败关闭策略拒绝执行",
                    List.copyOf(denyRules),
                    List.of(),
                    true
            );
        }
        List<?> sensitiveRules = listValue(permissionConfig, "sensitiveRules");
        if (sensitiveRules.isEmpty()) {
            return PermissionReviewResult.allow();
        }
        PermissionReviewResult.MaskInstruction instruction = new PermissionReviewResult.MaskInstruction(
                null, "$", "MASK_ALL", null, "未提供精确字段路径的敏感规则");
        return new PermissionReviewResult("allow", "", List.of(), List.of(instruction), false);
    }

    /**
     * 按确定性脱敏指令处理最终结果。
     *
     * @param finalResult 原始结果
     * @param reviewResult 本地权限审查结果
     * @return 原始结果或整份脱敏后的占位值
     */
    public Object maskFinalResult(Object finalResult, PermissionReviewResult reviewResult) {
        if (finalResult == null || reviewResult == null || reviewResult.maskInstructions().isEmpty()) {
            return finalResult;
        }
        return "***";
    }

    /**
     * 返回本次权限决策使用的确定性规则引擎版本。
     *
     * @return 规则引擎版本
     */
    public String reviewVersion() {
        return DETERMINISTIC_REVIEW_VERSION;
    }

    private List<?> listValue(Map<String, Object> permissionConfig, String key) {
        if (permissionConfig == null || permissionConfig.isEmpty()) {
            return List.of();
        }
        Object value = permissionConfig.get(key);
        return value instanceof List<?> list ? list : List.of();
    }
}
