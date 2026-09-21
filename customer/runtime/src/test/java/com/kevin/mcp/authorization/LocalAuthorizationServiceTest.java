package com.kevin.mcp.authorization;

import com.kevin.mcp.annotation.BatchMode;
import com.kevin.mcp.annotation.ExecutorType;
import com.kevin.mcp.annotation.OperationMode;
import com.kevin.mcp.capability.LocalCapabilityDescriptor;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证第一版 Runtime 只允许登记过的只读能力。
 *
 * @author Kevin
 * @date 2026-09-17
 */
class LocalAuthorizationServiceTest {

    /** 验证写能力即使出现在合法计划结构中也会被本地拒绝。 */
    @Test
    void shouldRejectWriteCapability() {
        PrivateMcpToolSchemaRegistry registry = mock(PrivateMcpToolSchemaRegistry.class);
        when(registry.getCapability("order.create")).thenReturn(Optional.of(new LocalCapabilityDescriptor(
                "order.create", 1, "method", OperationMode.WRITE,
                ExecutorType.JAVA, BatchMode.SINGLE, "ORDER")));
        LocalAuthorizationService service = new LocalAuthorizationService(registry);
        JsonExecutionStep step = new JsonExecutionStep("call", "s1", null, Map.of(), null,
                List.of(), null, null, List.of(), "order.create", 1);
        JsonExecutionPlan plan = new JsonExecutionPlan("p1", 1, "t1", "e1", "i1", "1.0.0",
                "artifact", "catalog", "", "2026-09-17T00:00:00Z", "BEST_EFFORT_CURRENT",
                "创建订单", List.of(step), null);

        assertThatThrownBy(() -> service.authorize(plan))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Write capability");
    }
}
