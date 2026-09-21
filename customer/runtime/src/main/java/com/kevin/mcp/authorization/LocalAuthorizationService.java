package com.kevin.mcp.authorization;

import com.kevin.mcp.annotation.OperationMode;
import com.kevin.mcp.capability.LocalCapabilityDescriptor;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionStep;
import org.springframework.stereotype.Service;

/**
 * 在客户 Runtime 内对能力存在性、版本和只读属性进行机械校验。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Service
public class LocalAuthorizationService {

    private final PrivateMcpToolSchemaRegistry registry;

    /**
     * 注入本地能力注册表。
     *
     * @param registry 本地能力注册表
     */
    public LocalAuthorizationService(PrivateMcpToolSchemaRegistry registry) {
        this.registry = registry;
    }

    /**
     * 校验计划内所有能力均已登记且属于第一版允许的只读能力。
     *
     * @param plan 执行计划
     */
    public void authorize(JsonExecutionPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("Execution plan is required");
        }
        for (JsonExecutionStep step : plan.steps()) {
            authorizeStep(step);
        }
    }

    /**
     * 校验单步能力并递归检查 foreach 循环体。
     *
     * @param step 当前步骤
     */
    private void authorizeStep(JsonExecutionStep step) {
        if (step.isCall()) {
            if (step.capabilityId() == null || step.capabilityId().isBlank()) {
                throw new SecurityException("Execution plan capability is missing");
            }
            LocalCapabilityDescriptor capability = registry.getCapability(step.capabilityId())
                    .orElseThrow(() -> new SecurityException("Execution plan capability is not registered"));
            if (step.capabilityVersion() == null || capability.version() != step.capabilityVersion()) {
                throw new SecurityException("Execution plan capability version does not match Runtime");
            }
            if (OperationMode.READ_ONLY != capability.mode()) {
                throw new SecurityException("Write capability is not supported by the read-only Runtime");
            }
        }
        for (JsonExecutionStep child : step.body()) {
            authorizeStep(child);
        }
    }
}
