package com.kevin.mcp.authorization;

import com.kevin.mcp.catalog.RuntimeCatalog;
import com.kevin.mcp.processor.JsonExecutionPlan;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Set;

/**
 * 校验云端计划与当前员工、Runtime 制品和能力目录严格绑定。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@Service
public class PlanBindingValidator {

    private static final int SUPPORTED_PLAN_CONTRACT_VERSION = 1;
    private static final Set<String> SUPPORTED_CONSISTENCY_MODES = Set.of("BEST_EFFORT_CURRENT");

    private final RuntimeCatalog runtimeCatalog;

    /**
     * 注入 Runtime 身份和 Catalog 摘要服务。
     *
     * @param runtimeCatalog Runtime Catalog module
     */
    public PlanBindingValidator(RuntimeCatalog runtimeCatalog) {
        this.runtimeCatalog = runtimeCatalog;
    }

    /**
     * 校验计划不能跨租户、跨员工、跨实例或跨版本执行。
     *
     * @param plan 云端执行计划
     * @param tenantId 当前已验证租户
     * @param employeeId 当前已验证员工
     */
    public void validate(JsonExecutionPlan plan, String tenantId, String employeeId) {
        if (plan == null || !Integer.valueOf(SUPPORTED_PLAN_CONTRACT_VERSION).equals(plan.planContractVersion())) {
            throw new SecurityException("Unsupported execution plan contract version");
        }
        requireEqual(tenantId, plan.tenantId(), "tenant");
        requireEqual(employeeId, plan.employeeId(), "employee");
        runtimeCatalog.requireExecutable(plan);
        if (!SUPPORTED_CONSISTENCY_MODES.contains(plan.consistencyMode())) {
            throw new SecurityException("Execution plan consistency mode is unsupported");
        }
        try {
            Instant.parse(plan.asOf());
        } catch (DateTimeException exception) {
            throw new SecurityException("Execution plan asOf is invalid", exception);
        }
    }

    private void requireEqual(String expected, String actual, String fieldName) {
        if (expected == null || !expected.equals(actual)) {
            throw new SecurityException(fieldName + " does not match the current request");
        }
    }
}
