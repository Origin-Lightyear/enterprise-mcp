package com.customer.mcp.capability;

import com.customer.mcp.wren.WrenSidecarClient;
import com.kevin.mcp.agent.model.EmployeeLlmInvocationConfig;
import com.kevin.mcp.clarification.ClarificationRequiredException;
import com.kevin.mcp.execution.RequestExecutionContext;
import com.kevin.mcp.execution.RequestExecutionContextHolder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * 验证 Wren 查询能力将 Adapter 歧义结果转换为 Runtime 可处理的领域请求。
 *
 * @author Kevin
 * @date 2026-09-21
 */
class WrenDatabaseToolTest {

    /** 验证结构化候选项不会被误当作数据库查询结果返回。 */
    @Test
    void shouldRaiseClarificationRequiredException() {
        WrenSidecarClient client = mock(WrenSidecarClient.class);
        doReturn(Map.of("ok", true, "code", "NEEDS_CLARIFICATION", "data", Map.of(
                        "outcome", "NEEDS_CLARIFICATION",
                        "sourceKey", "sales",
                        "mdlDigest", "mdl-digest",
                        "clarification", Map.of(
                                "id", "clarification-1",
                                "question", "销售日期是指哪个字段？",
                                "reason", "MULTIPLE_SEMANTIC_FIELDS",
                                "allowFreeText", true,
                                "options", List.of(
                                        Map.of("id", "Order.createdAt", "label", "下单时间"),
                                        Map.of("id", "Order.paidAt", "label", "支付时间")
                                )
                        )
                ))).when(client).query(anyMap());
        RequestExecutionContextHolder contextHolder = new RequestExecutionContextHolder();
        WrenDatabaseTool tool = new WrenDatabaseTool(client, contextHolder);
        RequestExecutionContext context = new RequestExecutionContext(
                new EmployeeLlmInvocationConfig("https://models.example/v1", "secret", "chat"),
                null, "mdl-digest");

        try (RequestExecutionContextHolder.Scope ignored = contextHolder.bind(context)) {
            assertThatThrownBy(() -> tool.query("sales", "查询销售日期", "table"))
                    .isInstanceOfSatisfying(ClarificationRequiredException.class, exception -> {
                        assertThat(exception.getRequest().sourceKey()).isEqualTo("sales");
                        assertThat(exception.getRequest().options()).hasSize(2);
                    });
        }
    }
}
