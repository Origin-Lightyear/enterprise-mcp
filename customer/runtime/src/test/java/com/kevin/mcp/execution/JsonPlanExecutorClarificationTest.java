package com.kevin.mcp.execution;

import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.clarification.ClarificationRequest;
import com.kevin.mcp.clarification.ClarificationRequiredException;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionStep;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证执行器保留能力方法抛出的澄清领域异常。
 *
 * @author Kevin
 * @date 2026-09-21
 */
class JsonPlanExecutorClarificationTest {

    /** 验证反射调用不会把澄清请求包装成普通执行失败。 */
    @Test
    void shouldPropagateClarificationRequiredException() throws Exception {
        PrivateMcpToolSchemaRegistry registry = mock(PrivateMcpToolSchemaRegistry.class);
        Fixture fixture = new Fixture();
        Method method = Fixture.class.getDeclaredMethod("query");
        when(registry.getRegisteredMethod("fixture.query")).thenReturn(Optional.of(method));
        when(registry.getRegisteredBean("fixture.query")).thenReturn(Optional.of(fixture));
        JsonPlanExecutor executor = new JsonPlanExecutor(registry, new ExecutionLimitsProperties());
        JsonExecutionStep step = new JsonExecutionStep("call", "step-1", "fixture.query", Map.of(),
                null, List.of(), null, null, List.of(), null, null);
        JsonExecutionPlan plan = new JsonExecutionPlan("plan-1", 1, "tenant-1", "employee-1",
                "instance-1", "1.0.0", "artifact", "catalog", "mdl-digest",
                "2026-09-21T00:00:00Z", "BEST_EFFORT_CURRENT", "查询", List.of(step), null);

        assertThatThrownBy(() -> executor.execute(plan))
                .isInstanceOf(ClarificationRequiredException.class);
    }

    /** 提供一个固定抛出澄清请求的只读能力。 */
    public static class Fixture {

        /**
         * 模拟 Wren 在执行前发现语义歧义。
         *
         * @return 永不返回
         */
        public Object query() {
            throw new ClarificationRequiredException(new ClarificationRequest(
                    "clarification-1", "请选择字段", "MULTIPLE_SEMANTIC_FIELDS",
                    List.of(new ClarificationRequest.ClarificationOption("Order.createdAt", "下单时间"),
                            new ClarificationRequest.ClarificationOption("Order.paidAt", "支付时间")),
                    false, "sales", "mdl-digest"));
        }
    }
}
