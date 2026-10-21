package com.kevin.mcp.clarification;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 MCP Elicitation 回答只能绑定服务端提供的 Wren 候选字段。
 *
 * @author Kevin
 * @date 2026-09-21
 */
class ClarificationElicitationServiceTest {

    private final ClarificationElicitationService service = new ClarificationElicitationService();

    /** 验证接受候选项后生成可供重新规划的稳定确认内容。 */
    @Test
    void shouldAcceptKnownSemanticField() {
        McpSyncRequestContext context = contextWith(new StructuredElicitResult<>(
                McpSchema.ElicitResult.Action.ACCEPT,
                new ClarificationElicitationService.ClarificationAnswer("Order.paidAt", ""), Map.of()));

        ClarificationResolution resolution = service.elicit(context, request());

        assertThat(resolution.accepted()).isTrue();
        assertThat(resolution.selectedOptionId()).isEqualTo("Order.paidAt");
        assertThat(resolution.confirmation()).contains("支付时间", "Order.paidAt");
    }

    /** 验证 Agent 不能提交 Wren 未提供的字段标识。 */
    @Test
    void shouldRejectUnknownSemanticField() {
        McpSyncRequestContext context = contextWith(new StructuredElicitResult<>(
                McpSchema.ElicitResult.Action.ACCEPT,
                new ClarificationElicitationService.ClarificationAnswer("Order.deletedAt", ""), Map.of()));

        assertThatThrownBy(() -> service.elicit(context, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown option");
    }

    /** 验证用户取消时不生成可继续执行的确认内容。 */
    @Test
    void shouldReturnCancelledResolution() {
        McpSyncRequestContext context = contextWith(new StructuredElicitResult<>(
                McpSchema.ElicitResult.Action.CANCEL, null, Map.of()));

        ClarificationResolution resolution = service.elicit(context, request());

        assertThat(resolution.accepted()).isFalse();
        assertThat(resolution.confirmation()).isEmpty();
    }

    private McpSyncRequestContext contextWith(
            StructuredElicitResult<ClarificationElicitationService.ClarificationAnswer> result) {
        McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        when(context.elicitEnabled()).thenReturn(true);
        when(context.elicit(any(), eq(ClarificationElicitationService.ClarificationAnswer.class)))
                .thenReturn(result);
        return context;
    }

    private ClarificationRequest request() {
        return new com.kevin.mcp.clarification.ClarificationRequest("clarification-1", "销售日期是指哪个字段？",
                "MULTIPLE_SEMANTIC_FIELDS", List.of(
                new ClarificationRequest.ClarificationOption("Order.createdAt", "下单时间"),
                new ClarificationRequest.ClarificationOption("Order.paidAt", "支付时间")
        ), true, "sales", "mdl-digest");
    }
}


