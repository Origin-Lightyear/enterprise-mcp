package com.kevin.mcp.authorization;

import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionStep;

import java.util.List;
import java.util.Map;

/**
 * 在客户 Runtime 内对执行计划进行不可绕过的能力白名单校验。
 *
 * @author Kevin
 * @date 2026-09-09
 */
public class LocalAuthorizationService {

    /**
     * 校验计划内所有能力均在员工本地授权范围内。
     *
     * @param plan 执行计划
     * @param permissionConfig 员工权限快照
     */
    public void authorize(JsonExecutionPlan plan, Map<String, Object> permissionConfig) {
        if (plan == null) {
            throw new IllegalArgumentException("Execution plan is required");
        }
        if (permissionConfig == null || permissionConfig.isEmpty()) {
            return;
        }
        Object allowed = permissionConfig.get("allowCapabilities");
        if (!(allowed instanceof List<?> allowList)) {
            return;
        }
        for (JsonExecutionStep step : plan.steps()) {
            authorizeStep(step, allowList);
        }
    }

    /**
     * 校验单步能力并递归检查 foreach 循环体。
     *
     * @param step 当前步骤
     * @param allowList 本地允许能力列表
     */
    private void authorizeStep(JsonExecutionStep step, List<?> allowList) {
        if (step.isCall() && step.capabilityId() != null && !step.capabilityId().isBlank()
                && !allowList.contains(step.capabilityId())) {
            throw new SecurityException("Local capability permission denied: " + step.capabilityId());
        }
        for (JsonExecutionStep child : step.body()) {
            authorizeStep(child, allowList);
        }
    }
}
