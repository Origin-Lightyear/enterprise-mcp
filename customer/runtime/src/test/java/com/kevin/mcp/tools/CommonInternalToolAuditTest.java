package com.kevin.mcp.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.identity.AgentVerify;
import com.kevin.mcp.agent.identity.DebugIdentityProvider;
import com.kevin.mcp.agent.identity.VerifiedEmployee;
import com.kevin.mcp.agent.model.EmployeeAuth;
import com.kevin.mcp.audit.ExecutionAuditService;
import com.kevin.mcp.audit.PermissionAuditOutboxService;
import com.kevin.mcp.authorization.LocalAuthorizationService;
import com.kevin.mcp.authorization.PlanBindingValidator;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.catalog.RuntimeCatalog;
import com.kevin.mcp.clarification.ClarificationElicitationService;
import com.kevin.mcp.execution.JsonPlanExecutor;
import com.kevin.mcp.execution.JsonPlanParser;
import com.kevin.mcp.execution.RequestExecutionContextHolder;
import com.kevin.mcp.planning.PlanningService;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionResult;
import com.kevin.mcp.processor.JsonExecutionStep;
import com.kevin.mcp.util.GsonUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证内部 MCP 执行入口上报确定性数据源授权审计。
 *
 * @author Kevin
 * @date 2026-10-26
 */
class CommonInternalToolAuditTest {

    /** 验证审计不再包含自然语言规则、权限模型或敏感业务参数。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldReportDeterministicDataSourceAuthorization() throws Exception {
        AgentVerify verifier = mock(AgentVerify.class);
        PlanningService planner = mock(PlanningService.class);
        JsonPlanParser parser = mock(JsonPlanParser.class);
        JsonPlanExecutor executor = mock(JsonPlanExecutor.class);
        HttpAgentServerClient client = mock(HttpAgentServerClient.class);
        ExecutionAuditService localAudit = mock(ExecutionAuditService.class);
        PermissionAuditOutboxService outbox = mock(PermissionAuditOutboxService.class);
        McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        EmployeeAuth employee = new EmployeeAuth(1001L, "ACTIVE", 7L, Map.of(),
                Map.of("databasePermissions", List.of(Map.of(
                        "instanceId", "instance-1", "dataSourceId", "sales", "queryAllowed", true))));
        JsonExecutionStep step = new JsonExecutionStep("call", "step-1", "fixture.query",
                Map.of("sql", "SELECT secret FROM private_table"), null, List.of(), null, null,
                List.of(), "cap.fixture.query", 1);
        JsonExecutionPlan plan = new JsonExecutionPlan("plan-1", 1, "1", 1001L, "instance-1", "1.0",
                "artifact", "catalog", "", "2026-10-24T00:00:00Z", "BEST_EFFORT_CURRENT",
                "查询销售日期", List.of(step), null);
        when(verifier.verifySourceAndGetEmployee(context)).thenReturn(new VerifiedEmployee("1", employee));
        when(planner.plan(anyString(), anyString(), anyLong())).thenReturn("raw-plan");
        when(parser.parse("raw-plan")).thenReturn(plan);
        when(executor.execute(any(), any())).thenReturn(new JsonExecutionResult("plan-1", "查询销售日期",
                true, Map.of("secretResult", "业务原始数据"), Map.of(), List.of("step-1")));
        CommonInternalTool tool = new CommonInternalTool(verifier, planner, parser, executor, client,
                mock(LocalAuthorizationService.class), mock(PlanBindingValidator.class), localAudit, outbox,
                new RequestExecutionContextHolder(), mock(PrivateMcpToolSchemaRegistry.class),
                mock(ObjectProvider.class), mock(RuntimeCatalog.class), mock(ClarificationElicitationService.class),
                mock(DebugIdentityProvider.class));

        tool.execute("查询销售日期", context);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(outbox).enqueue(payloadCaptor.capture());
        JsonObject payload = JsonParser.parseString(GsonUtil.toJson(payloadCaptor.getValue())).getAsJsonObject();
        assertThat(payload.get("requestMessage").getAsString()).isEqualTo("查询销售日期");
        assertThat(payload.get("authorizationMode").getAsString()).isEqualTo("DATA_SOURCE_POLICY");
        assertThat(payload.getAsJsonObject("reviewResult").get("authorizationMode").getAsString())
                .isEqualTo("DATA_SOURCE_POLICY");
        assertThat(payload.getAsJsonObject("reviewResult").get("modelInvoked").getAsBoolean()).isFalse();
        assertThat(payload.getAsJsonArray("matchedRules")).isEmpty();
        assertThat(payload.has("modelVersion")).isFalse();
        assertThat(payload.toString()).doesNotContain(
                "SELECT secret", "业务原始数据", "denyRules", "sensitiveRules", "maskInstructions");
        ArgumentCaptor<Map<String, Object>> localCaptor = ArgumentCaptor.forClass(Map.class);
        verify(localAudit).recordDecision(localCaptor.capture());
        assertThat(localCaptor.getValue()).containsEntry("authorizationMode", "DATA_SOURCE_POLICY");
    }
}

