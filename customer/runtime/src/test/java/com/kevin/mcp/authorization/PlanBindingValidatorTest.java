package com.kevin.mcp.authorization;

import com.kevin.mcp.catalog.RuntimeCatalog;
import com.kevin.mcp.processor.JsonExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 验证云端计划与当前 Runtime 身份严格绑定。
 *
 * @author Kevin
 * @date 2026-09-17
 */
class PlanBindingValidatorTest {

    /** 验证跨员工复用计划会被拒绝。 */
    @Test
    void shouldRejectPlanBoundToAnotherEmployee() {
        RuntimeCatalog runtimeCatalog = mock(RuntimeCatalog.class);
        PlanBindingValidator validator = new PlanBindingValidator(runtimeCatalog);
        JsonExecutionPlan plan = new JsonExecutionPlan("p1", 1, "tenant-1", "employee-2",
                "instance-1", "1.0.0", "artifact", "catalog", "", "2026-09-17T00:00:00Z",
                "BEST_EFFORT_CURRENT", "查询", List.of(), "无法生成计划");

        assertThatThrownBy(() -> validator.validate(plan, "tenant-1", "employee-1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("employee");
    }
}
