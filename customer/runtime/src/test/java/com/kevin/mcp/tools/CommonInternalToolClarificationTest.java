package com.kevin.mcp.tools;
import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.client.PlanningGatewayClient;
import com.kevin.mcp.agent.configuration.DebugBypassProperties;
import com.kevin.mcp.agent.configuration.RuntimeArtifactDigest;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.agent.identity.AgentVerify;
import com.kevin.mcp.agent.identity.DebugIdentityProvider;
import com.kevin.mcp.agent.identity.VerifiedEmployee;
import com.kevin.mcp.agent.model.EmployeeAuth;
import com.kevin.mcp.audit.ExecutionAuditService;
import com.kevin.mcp.audit.PermissionAuditOutboxService;
import com.kevin.mcp.authorization.LocalAuthorizationService;
import com.kevin.mcp.authorization.PermissionReviewResult;
import com.kevin.mcp.authorization.PermissionReviewService;
import com.kevin.mcp.authorization.PlanBindingValidator;
import com.kevin.mcp.annotation.BatchMode;
import com.kevin.mcp.annotation.ExecutorType;
import com.kevin.mcp.annotation.OperationMode;
import com.kevin.mcp.capability.LocalCapabilityDescriptor;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.catalog.CurrentMdlAdapter;
import com.kevin.mcp.catalog.PlanningBinding;
import com.kevin.mcp.catalog.RuntimeCatalog;
import com.kevin.mcp.catalog.RuntimeCatalogService;
import com.kevin.mcp.clarification.ClarificationElicitationService;
import com.kevin.mcp.clarification.ClarificationRequest;
import com.kevin.mcp.clarification.ClarificationRequiredException;
import com.kevin.mcp.clarification.ClarificationResolution;
import com.kevin.mcp.execution.K3BusinessException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
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
        DebugBypassProperties debugProperties = new DebugBypassProperties();
        DebugIdentityProvider debugIdentityProvider = new DebugIdentityProvider(
                agentClient, debugProperties, "tenant-1");
        EmployeeAuth employeeAuth = new EmployeeAuth(1001L, "ACTIVE", 1L, Map.of(), Map.of());
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
        when(planningService.plan(anyString(), anyString(), anyLong())).thenReturn("raw-1", "raw-2");
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
                elicitationService, debugIdentityProvider);

        Map<Boolean, String> result = tool.execute("查询销售日期", requestContext);

        assertThat(result).containsKey(true);
        assertThat(result.get(true)).contains("executionReceipt", "total");
        ArgumentCaptor<String> intentCaptor = ArgumentCaptor.forClass(String.class);
        verify(planningService, times(2)).plan(intentCaptor.capture(), anyString(), anyLong());
        assertThat(intentCaptor.getAllValues().get(0)).isEqualTo("查询销售日期");
        assertThat(intentCaptor.getAllValues().get(1)).contains("Order.paidAt", "clarification-1");
        verify(auditService).recordClarification("tenant-1", 1001L, clarification, resolution);
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
        DebugBypassProperties debugProperties = new DebugBypassProperties();
        DebugIdentityProvider debugIdentityProvider = new DebugIdentityProvider(
                agentClient, debugProperties, "tenant-1");
        EmployeeAuth employeeAuth = new EmployeeAuth(1001L, "ACTIVE", 1L, Map.of(), Map.of());
        VerifiedEmployee employee = new VerifiedEmployee("tenant-1", employeeAuth);
        ClarificationRequest clarification = clarification();
        ClarificationResolution resolution = new ClarificationResolution(
                McpSchema.ElicitResult.Action.ACCEPT,
                "用户已确认选择“支付时间”（语义字段：Order.paidAt）", "Order.paidAt", "");

        when(agentVerify.verifySourceAndGetEmployee(requestContext)).thenReturn(employee);
        when(planningService.plan(anyString(), anyString(), anyLong())).thenReturn("raw-1");
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
                elicitationService, debugIdentityProvider);

        Map<Boolean, String> result = tool.execute("查询销售日期", requestContext);

        assertThat(result.get(true)).contains("语义模型已更新", "重新发起查询");
        verify(planningService).plan(anyString(), anyString(), anyLong());
        verify(auditService).recordClarification("tenant-1", 1001L, clarification, resolution);
    }

    /** 验证调试总开关复用真实员工后跳过来源校验、权限审查、脱敏和审计，同时保留本地授权与目录绑定。 */
    @Test
    void shouldBypassReviewAndAuditWhileDebugEnabled() throws Exception {
        AgentVerify agentVerify = mock(AgentVerify.class);
        PlanningService planningService = mock(PlanningService.class);
        JsonPlanParser parser = mock(JsonPlanParser.class);
        JsonPlanExecutor executor = mock(JsonPlanExecutor.class);
        PermissionReviewService reviewService = mock(PermissionReviewService.class);
        HttpAgentServerClient agentClient = mock(HttpAgentServerClient.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        PermissionAuditOutboxService auditOutbox = mock(PermissionAuditOutboxService.class);
        PrivateMcpToolSchemaRegistry schemaRegistry = mock(PrivateMcpToolSchemaRegistry.class);
        ClarificationElicitationService elicitationService = mock(ClarificationElicitationService.class);
        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        DebugBypassProperties debugProperties = new DebugBypassProperties();
        debugProperties.setEnabled(true);
        debugProperties.setEmployeeId(1001L);
        DebugIdentityProvider debugIdentityProvider = new DebugIdentityProvider(
                agentClient, debugProperties, "tenant-1");

        when(schemaRegistry.getCloudPlanningSchemas()).thenReturn(List.of(
                new PrivateMcpToolSchemaRegistry.CloudPlanningSchema("cap.employee.query", 1,
                        "查询员工", null, null, "JAVA", "SINGLE", "READ_ONLY", "EMPLOYEE")));
        when(schemaRegistry.getCapability("cap.employee.query")).thenReturn(Optional.of(
                new LocalCapabilityDescriptor("cap.employee.query", 1, "employee.query",
                        OperationMode.READ_ONLY, ExecutorType.JAVA, BatchMode.SINGLE, "EMPLOYEE")));
        PlanningGatewayClient gatewayClient = mock(PlanningGatewayClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentMdlAdapter> mdlProvider = mock(ObjectProvider.class);
        when(mdlProvider.getIfAvailable()).thenReturn(null);
        RuntimeIdentityProperties identity = new RuntimeIdentityProperties();
        identity.setTenantId("tenant-1");
        identity.setInstanceId("instance-1");
        identity.setArtifactVersion("1.0.0");
        identity.setArtifactDigest("sha256:artifact");
        RuntimeArtifactDigest artifactDigest = mock(RuntimeArtifactDigest.class);
        when(artifactDigest.getValue()).thenReturn("sha256:" + "a".repeat(64));
        when(gatewayClient.confirmCatalog(anyMap())).thenAnswer(invocation -> {
            Map<?, ?> request = invocation.getArgument(0);
            return Map.of("catalogDigest", request.get("catalogDigest"));
        });
        when(gatewayClient.referenceCatalog(anyMap())).thenAnswer(invocation -> Map.of("saved", true));
        RuntimeCatalog runtimeCatalog = new RuntimeCatalogService(
                schemaRegistry, mdlProvider, identity, gatewayClient, artifactDigest);
        runtimeCatalog.synchronize();
        PlanningBinding binding = runtimeCatalog.requirePlanningBinding();

        JsonExecutionStep step = new JsonExecutionStep("call", "step-1", "employee.query",
                Map.of("name", "Kevin"), null, List.of(), null, null, List.of(),
                "cap.employee.query", 1);
        JsonExecutionPlan executionPlan = new JsonExecutionPlan("plan-debug", 1,
                binding.tenantId(), 1001L, binding.instanceId(), binding.artifactVersion(),
                binding.artifactDigest(), binding.catalogDigest(), binding.mdlDigest(),
                "2026-10-15T00:00:00Z", "BEST_EFFORT_CURRENT", "查询员工", List.of(step), null);
        JsonExecutionResult executionResult = new JsonExecutionResult("plan-debug", "查询员工", true,
                Map.of("mobile", "13800000000"), Map.of(), List.of("step-1"));
        when(agentClient.fetchTenantAuth("tenant-1", 1001L)).thenReturn(
                new EmployeeAuth(1001L, "ENABLED", 7L, Map.of(), Map.of()));
        when(planningService.plan("查询员工", "tenant-1", 1001L)).thenReturn("raw-debug");
        when(parser.parse("raw-debug")).thenReturn(executionPlan);
        when(executor.execute(org.mockito.ArgumentMatchers.eq(executionPlan), any())).thenReturn(executionResult);
        when(reviewService.maskFinalResult(any(), any()))
                .thenThrow(new AssertionError("debug bypass must not mask the final result"));
        when(reviewService.requiresReview(Map.of())).thenReturn(false);
        when(reviewService.review(anyString(), any(), any(), any(), any())).thenReturn(PermissionReviewResult.allow());
        when(reviewService.reviewVersion()).thenReturn("review-v1");
        when(auditOutbox.enqueue(any())).thenReturn("audit-1");

        CommonInternalTool tool = new CommonInternalTool(agentVerify, planningService, parser, executor,
                reviewService, agentClient, new LocalAuthorizationService(schemaRegistry),
                new PlanBindingValidator(runtimeCatalog), auditService, auditOutbox,
                new RequestExecutionContextHolder(), schemaRegistry, modelProvider(), runtimeCatalog,
                elicitationService, debugIdentityProvider);

        Map<Boolean, String> result = tool.execute("查询员工", requestContext);

        assertThat(result.get(true))
                .contains("13800000000")
                .doesNotContain("****");
        verifyNoInteractions(agentVerify, auditService, auditOutbox);
        verify(reviewService, never()).review(anyString(), any(), any(), any(), any());
        verify(reviewService, never()).maskFinalResult(any(), any());
        verify(agentClient).fetchTenantAuth("tenant-1", 1001L);

    }

    @SuppressWarnings("unchecked")

    /**
     * 验证金蝶业务错误不再被折叠为管理员提示，而是把建议透传给 Agent。
     */
    @Test
    void k3BusinessErrorIsTransmittedToAgent() throws Exception {
        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        AgentVerify agentVerify = mock(AgentVerify.class);
        PlanningService planningService = mock(PlanningService.class);
        JsonPlanParser parser = mock(JsonPlanParser.class);
        JsonPlanExecutor executor = mock(JsonPlanExecutor.class);
        PermissionReviewService reviewService = mock(PermissionReviewService.class);
        HttpAgentServerClient agentClient = mock(HttpAgentServerClient.class);
        ExecutionAuditService auditService = mock(ExecutionAuditService.class);
        PermissionAuditOutboxService auditOutbox = mock(PermissionAuditOutboxService.class);
        ClarificationElicitationService elicitationService = mock(ClarificationElicitationService.class);

        VerifiedEmployee verifiedEmployee = new VerifiedEmployee("tenant-1",
                new EmployeeAuth(1001L, "ENABLED", 7L, Map.of(), Map.of()));
        when(agentVerify.verifySourceAndGetEmployee(requestContext)).thenReturn(verifiedEmployee);
        when(planningService.plan("查询金蝶", "tenant-1", 1001L))
                .thenThrow(new K3BusinessException("分页取数需要排序条件或者单据有设置主键 | 建议: 传 order"));

        CommonInternalTool tool = new CommonInternalTool(agentVerify, planningService, parser, executor,
                reviewService, agentClient, new LocalAuthorizationService(mock(PrivateMcpToolSchemaRegistry.class)),
                new PlanBindingValidator(mock(RuntimeCatalog.class)), auditService, auditOutbox,
                new RequestExecutionContextHolder(), mock(PrivateMcpToolSchemaRegistry.class), modelProvider(), mock(RuntimeCatalog.class),
                elicitationService, mock(DebugIdentityProvider.class));

        Map<Boolean, String> result = tool.execute("查询金蝶", requestContext);

        assertThat(result.get(false))
                .contains("分页取数需要排序条件")
                .contains("建议")
                .doesNotContain("请联系管理员查看本地审计");
    }

    private ObjectProvider<SemanticModelSnapshotProvider> modelProvider() {
        return mock(ObjectProvider.class);
    }

    private JsonExecutionPlan plan(String planId) {
        JsonExecutionStep step = new JsonExecutionStep("call", "step-1", "fixture.query", Map.of(),
                null, List.of(), null, null, List.of(), null, null);
        return new JsonExecutionPlan(planId, 1, "tenant-1", 1001L, "instance-1", "1.0.0",
                "artifact", "catalog", "mdl-digest", "2026-09-21T00:00:00Z",
                "BEST_EFFORT_CURRENT", "查询销售日期", List.of(step), null);
    }

    private ClarificationRequest clarification() {
        return new com.kevin.mcp.clarification.ClarificationRequest("clarification-1", "销售日期是指哪个字段？",
                "MULTIPLE_SEMANTIC_FIELDS", List.of(
                new ClarificationRequest.ClarificationOption("Order.createdAt", "下单时间"),
                new ClarificationRequest.ClarificationOption("Order.paidAt", "支付时间")
        ), true, "sales", "mdl-digest");
    }
}




