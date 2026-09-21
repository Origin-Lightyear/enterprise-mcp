package com.kevin.mcp.tools;

import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.identity.AgentVerify;
import com.kevin.mcp.agent.identity.VerifiedEmployee;
import com.kevin.mcp.agent.model.EmployeeAuth;
import com.kevin.mcp.audit.ExecutionAuditService;
import com.kevin.mcp.audit.PermissionAuditOutboxService;
import com.kevin.mcp.authorization.LocalAuthorizationService;
import com.kevin.mcp.authorization.PermissionReviewResult;
import com.kevin.mcp.authorization.PermissionReviewService;
import com.kevin.mcp.authorization.PlanBindingValidator;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.catalog.RuntimeCatalog;
import com.kevin.mcp.clarification.ClarificationElicitationService;
import com.kevin.mcp.clarification.ClarificationRequest;
import com.kevin.mcp.clarification.ClarificationRequiredException;
import com.kevin.mcp.clarification.ClarificationResolution;
import com.kevin.mcp.execution.JsonPlanExecutor;
import com.kevin.mcp.execution.JsonPlanParser;
import com.kevin.mcp.execution.RequestExecutionContextHolder;
import com.kevin.mcp.execution.SemanticModelSnapshot;
import com.kevin.mcp.execution.SemanticModelSnapshotProvider;
import com.kevin.mcp.planning.PlanningService;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionResult;
import com.kevin.mcp.processor.JsonExecutionStep;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Wren 澄清后整轮重新规划并执行，而不是继续使用旧计划。
 *
 * @author Kevin
 * @date 2026-09-21
 */
class CommonInternalToolClarificationTest {

    /** 验证接受字段候选后重新规划并返回最终结果。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldReplanWithAcceptedClarification() throws Exception {
        AgentVerify agentVerify = mock(AgentVerify.class);
        PlanningService planningService = mock(PlanningService.class);
        JsonPlanParser parser = mock(JsonPlanParser.class);
        JsonPlanExecutor executor = mock(JsonPlanExecutor.class);
        PermissionReviewService reviewService = mock(PermissionReviewService.class);
        HttpAgentServerClient agentClient = mock(HttpAgentServerClient.class);
        LocalAuthorizationService authorizationService = mock(LocalAuthorizationService.class);
        PlanBindingValidator bindingValidator = mock(PlanBindingValidator.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        PermissionAuditOutboxService auditOutbox = mock(PermissionAuditOutboxService.class);
        PrivateMcpToolSchemaRegistry schemaRegistry = mock(PrivateMcpToolSchemaRegistry.class);
        RuntimeCatalog runtimeCatalog = mock(RuntimeCatalog.class);
        ClarificationElicitationService elicitationService = mock(ClarificationElicitationService.class);
        ObjectProvider<SemanticModelSnapshotProvider> modelProvider = mock(ObjectProvider.class);
        SemanticModelSnapshotProvider snapshotProvider = mock(SemanticModelSnapshotProvider.class);
        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        EmployeeAuth employeeAuth = new EmployeeAuth("employee-1", "ACTIVE", 1L, Map.of(), Map.of());
        VerifiedEmployee employee = new VerifiedEmployee("tenant-1", employeeAuth);
        JsonExecutionPlan firstPlan = plan("plan-1");
        JsonExecutionPlan secondPlan = plan("plan-2");
        ClarificationRequest clarification = clarification();
        ClarificationResolution resolution = new ClarificationResolution(
                McpSchema.ElicitResult.Action.ACCEPT,
                "用户已确认选择“支付时间”（语义字段：Order.paidAt）", "Order.paidAt", "");
        JsonExecutionResult executionResult = new JsonExecutionResult(
                "plan-2", "查询销售日期", true, Map.of("total", 10), Map.of(), List.of("step-1"));

        when(agentVerify.verifySourceAndGetEmployee(requestContext)).thenReturn(employee);
        when(planningService.plan(anyString(), anyString(), anyString())).thenReturn("raw-1", "raw-2");
        when(parser.parse("raw-1")).thenReturn(firstPlan);
        when(parser.parse("raw-2")).thenReturn(secondPlan);
        when(reviewService.requiresReview(Map.of())).thenReturn(false);
        when(reviewService.review(anyString(), any(), any(), any(), any())).thenReturn(PermissionReviewResult.allow());
        when(reviewService.reviewVersion()).thenReturn("review-v1");
        when(reviewService.maskFinalResult(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditOutbox.enqueue(any())).thenReturn("audit-1");
        when(modelProvider.getIfAvailable()).thenReturn(snapshotProvider);
        when(snapshotProvider.snapshot()).thenReturn(new SemanticModelSnapshot("mdl-digest", Map.of("sources", Map.of())));
        when(executor.execute(any(), any()))
                .thenThrow(new ClarificationRequiredException(clarification))
                .thenReturn(executionResult);
        when(elicitationService.elicit(requestContext, clarification)).thenReturn(resolution);
        doNothing().when(runtimeCatalog).requireExecutable(any());

        CommonInternalTool tool = new CommonInternalTool(agentVerify, planningService, parser, executor,
                reviewService, agentClient, authorizationService, bindingValidator, auditService, auditOutbox,
                new RequestExecutionContextHolder(), schemaRegistry, modelProvider, runtimeCatalog,
                elicitationService);

        Map<Boolean, String> result = tool.execute("查询销售日期", requestContext);

        assertThat(result).containsKey(true);
        assertThat(result.get(true)).contains("executionReceipt", "total");
        ArgumentCaptor<String> intentCaptor = ArgumentCaptor.forClass(String.class);
        verify(planningService, times(2)).plan(intentCaptor.capture(), anyString(), anyString());
        assertThat(intentCaptor.getAllValues().get(0)).isEqualTo("查询销售日期");
        assertThat(intentCaptor.getAllValues().get(1)).contains("Order.paidAt", "clarification-1");
        verify(auditService).recordClarification("tenant-1", "employee-1", clarification, resolution);
    }

    /** 验证等待用户回答期间 MDL 更新时终止重规划。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldStopWhenMdlChangesDuringElicitation() throws Exception {
        AgentVerify agentVerify = mock(AgentVerify.class);
        PlanningService planningService = mock(PlanningService.class);
        JsonPlanParser parser = mock(JsonPlanParser.class);
        JsonPlanExecutor executor = mock(JsonPlanExecutor.class);
        PermissionReviewService reviewService = mock(PermissionReviewService.class);
        HttpAgentServerClient agentClient = mock(HttpAgentServerClient.class);
        LocalAuthorizationService authorizationService = mock(LocalAuthorizationService.class);
        PlanBindingValidator bindingValidator = mock(PlanBindingValidator.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        PermissionAuditOutboxService auditOutbox = mock(PermissionAuditOutboxService.class);
        PrivateMcpToolSchemaRegistry schemaRegistry = mock(PrivateMcpToolSchemaRegistry.class);
        RuntimeCatalog runtimeCatalog = mock(RuntimeCatalog.class);
        ClarificationElicitationService elicitationService = mock(ClarificationElicitationService.class);
        ObjectProvider<SemanticModelSnapshotProvider> modelProvider = mock(ObjectProvider.class);
        SemanticModelSnapshotProvider snapshotProvider = mock(SemanticModelSnapshotProvider.class);
        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        EmployeeAuth employeeAuth = new EmployeeAuth("employee-1", "ACTIVE", 1L, Map.of(), Map.of());
        VerifiedEmployee employee = new VerifiedEmployee("tenant-1", employeeAuth);
        ClarificationRequest clarification = clarification();
        ClarificationResolution resolution = new ClarificationResolution(
                McpSchema.ElicitResult.Action.ACCEPT,
                "用户已确认选择“支付时间”（语义字段：Order.paidAt）", "Order.paidAt", "");

        when(agentVerify.verifySourceAndGetEmployee(requestContext)).thenReturn(employee);
        when(planningService.plan(anyString(), anyString(), anyString())).thenReturn("raw-1");
        when(parser.parse("raw-1")).thenReturn(plan("plan-1"));
        when(reviewService.requiresReview(Map.of())).thenReturn(false);
        when(reviewService.review(anyString(), any(), any(), any(), any())).thenReturn(PermissionReviewResult.allow());
        when(reviewService.reviewVersion()).thenReturn("review-v1");
        when(auditOutbox.enqueue(any())).thenReturn("audit-1");
        when(modelProvider.getIfAvailable()).thenReturn(snapshotProvider);
        when(snapshotProvider.snapshot()).thenReturn(
                new SemanticModelSnapshot("mdl-digest", Map.of("sources", Map.of())),
                new SemanticModelSnapshot("updated-digest", Map.of("sources", Map.of())));
        when(executor.execute(any(), any())).thenThrow(new ClarificationRequiredException(clarification));
        when(elicitationService.elicit(requestContext, clarification)).thenReturn(resolution);

        CommonInternalTool tool = new CommonInternalTool(agentVerify, planningService, parser, executor,
                reviewService, agentClient, authorizationService, bindingValidator, auditService, auditOutbox,
                new RequestExecutionContextHolder(), schemaRegistry, modelProvider, runtimeCatalog,
                elicitationService);

        Map<Boolean, String> result = tool.execute("查询销售日期", requestContext);

        assertThat(result.get(true)).contains("语义模型已更新", "重新发起查询");
        verify(planningService).plan(anyString(), anyString(), anyString());
        verify(auditService).recordClarification("tenant-1", "employee-1", clarification, resolution);
    }

    private JsonExecutionPlan plan(String planId) {
        JsonExecutionStep step = new JsonExecutionStep("call", "step-1", "fixture.query", Map.of(),
                null, List.of(), null, null, List.of(), null, null);
        return new JsonExecutionPlan(planId, 1, "tenant-1", "employee-1", "instance-1", "1.0.0",
                "artifact", "catalog", "mdl-digest", "2026-09-21T00:00:00Z",
                "BEST_EFFORT_CURRENT", "查询销售日期", List.of(step), null);
    }

    private ClarificationRequest clarification() {
        return new ClarificationRequest("clarification-1", "销售日期是指哪个字段？",
                "MULTIPLE_SEMANTIC_FIELDS", List.of(
                new ClarificationRequest.ClarificationOption("Order.createdAt", "下单时间"),
                new ClarificationRequest.ClarificationOption("Order.paidAt", "支付时间")
        ), true, "sales", "mdl-digest");
    }
}
