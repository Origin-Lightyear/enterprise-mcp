package com.kevin.mcp.security;

import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.configuration.DebugBypassProperties;
import com.kevin.mcp.agent.identity.AgentVerify;
import com.kevin.mcp.agent.identity.DebugIdentityProvider;
import com.kevin.mcp.agent.identity.VerifiedEmployee;
import com.kevin.mcp.agent.model.EmployeeAuth;
import com.kevin.mcp.audit.ExecutionAuditService;
import com.kevin.mcp.annotation.BatchMode;
import com.kevin.mcp.annotation.ExecutorType;
import com.kevin.mcp.annotation.OperationMode;
import com.kevin.mcp.annotation.PublicMcpTool;
import com.kevin.mcp.authorization.LocalAuthorizationService;
import com.kevin.mcp.authorization.PermissionReviewService;
import com.kevin.mcp.capability.LocalCapabilityDescriptor;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.execution.RequestExecutionContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证公开工具调试旁路复用真实员工、保留限流并跳过权限审查和审计。
 *
 * @author Kevin
 * @date 2026-10-15
 */
class PublicMcpToolSecurityAspectDebugTest {

    /** 调试模式必须真实取员工，保留公开能力声明和限流，但不再审查或记录公开调用。 */
    @Test
    void shouldKeepRateLimitAndIdentityWhileBypassingReviewAndAudit() throws Throwable {
        AgentVerify agentVerify = mock(AgentVerify.class);
        PrivateMcpToolSchemaRegistry registry = mock(PrivateMcpToolSchemaRegistry.class);
        PermissionReviewService permissionReview = mock(PermissionReviewService.class);
        HttpAgentServerClient agentClient = mock(HttpAgentServerClient.class);
        ExecutionAuditService audit = mock(ExecutionAuditService.class);
        DebugBypassProperties properties = new DebugBypassProperties();
        properties.setEnabled(true);
        properties.setEmployeeId(1001L);
        DebugIdentityProvider debugIdentityProvider = new DebugIdentityProvider(
                agentClient, properties, "tenant-1");
        when(agentClient.fetchTenantAuth("tenant-1", 1001L)).thenReturn(
                new EmployeeAuth(1001L, "ENABLED", 7L, Map.of(), Map.of()));
        when(registry.getCapability("k3.metadata")).thenReturn(Optional.of(new LocalCapabilityDescriptor(
                "k3.metadata", 1, "k3.metadata", OperationMode.READ_ONLY,
                ExecutorType.JAVA, BatchMode.SINGLE, "METADATA")));
        PublicMcpToolSecurityAspect aspect = new PublicMcpToolSecurityAspect(
                agentVerify, debugIdentityProvider,
                new ToolSecurityPipeline(new LocalAuthorizationService(registry), permissionReview,
                        new RequestExecutionContextHolder()), agentClient, audit);

        ProceedingJoinPoint invocation = mock(ProceedingJoinPoint.class);
        McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
        when(invocation.getArgs()).thenReturn(new Object[]{requestContext});
        when(invocation.proceed()).thenReturn(Map.of("outcome", "OK"));

        Object result = aspect.guard(invocation, capability());

        assertThat(result).isEqualTo(Map.of("outcome", "OK"));
        verify(agentClient).fetchTenantAuth("tenant-1", 1001L);
        verify(invocation).proceed();
        verifyNoInteractions(agentVerify, permissionReview, audit);
    }

    /** 调试模式限流仍生效，但拒绝调用不写审计。 */
    @Test
    void shouldStillRateLimitWithoutAuditWhenDebugEnabled() throws Throwable {
        AgentVerify agentVerify = mock(AgentVerify.class);
        PrivateMcpToolSchemaRegistry registry = mock(PrivateMcpToolSchemaRegistry.class);
        PermissionReviewService permissionReview = mock(PermissionReviewService.class);
        HttpAgentServerClient agentClient = mock(HttpAgentServerClient.class);
        ExecutionAuditService audit = mock(ExecutionAuditService.class);
        DebugBypassProperties properties = new DebugBypassProperties();
        properties.setEnabled(true);
        properties.setEmployeeId(1001L);
        DebugIdentityProvider debugIdentityProvider = new DebugIdentityProvider(
                agentClient, properties, "tenant-1");
        when(agentClient.fetchTenantAuth("tenant-1", 1001L)).thenReturn(
                new EmployeeAuth(1001L, "ENABLED", 7L, Map.of(), Map.of()));
        when(registry.getCapability("k3.metadata")).thenReturn(Optional.of(new LocalCapabilityDescriptor(
                "k3.metadata", 1, "k3.metadata", OperationMode.READ_ONLY,
                ExecutorType.JAVA, BatchMode.SINGLE, "METADATA")));
        PublicMcpToolSecurityAspect aspect = new PublicMcpToolSecurityAspect(
                agentVerify, debugIdentityProvider,
                new ToolSecurityPipeline(new LocalAuthorizationService(registry), permissionReview,
                        new RequestExecutionContextHolder()), agentClient, audit);

        Object last = null;
        for (int i = 0; i < 31; i++) {
            ProceedingJoinPoint invocation = mock(ProceedingJoinPoint.class);
            McpSyncRequestContext requestContext = mock(McpSyncRequestContext.class);
            when(invocation.getArgs()).thenReturn(new Object[]{requestContext});
            when(invocation.proceed()).thenReturn(Map.of("outcome", "OK"));
            last = aspect.guard(invocation, capability());
        }

        assertThat(last).isEqualTo(Map.of(
                "outcome", "RATE_LIMITED", "message", "探索频率超限，请稍后再试"));
        verify(audit, never()).recordPublicToolCall(eq("tenant-1"), eq(1001L),
                eq("k3.metadata"), any(), any(), any());
    }

    /** 相同参数短 TTL 内复用审查结果，不重复调用权限模型。 */
    @Test
    void shouldReuseCachedReviewForRepeatedArguments() throws Throwable {
        AgentVerify agentVerify = mock(AgentVerify.class);
        PrivateMcpToolSchemaRegistry registry = mock(PrivateMcpToolSchemaRegistry.class);
        PermissionReviewService permissionReview = mock(PermissionReviewService.class);
        HttpAgentServerClient agentClient = mock(HttpAgentServerClient.class);
        ExecutionAuditService audit = mock(ExecutionAuditService.class);
        DebugBypassProperties properties = new DebugBypassProperties();
        DebugIdentityProvider debugIdentityProvider = new DebugIdentityProvider(
                agentClient, properties, "tenant-1");
        when(agentClient.fetchTenantAuth("tenant-1", 1001L)).thenReturn(
                new EmployeeAuth(1001L, "ENABLED", 7L, Map.of(), Map.of()));
        when(registry.getCapability("k3.metadata")).thenReturn(Optional.of(new LocalCapabilityDescriptor(
                "k3.metadata", 1, "k3.metadata", OperationMode.READ_ONLY,
                ExecutorType.JAVA, BatchMode.SINGLE, "METADATA")));
        PublicMcpToolSecurityAspect aspect = new PublicMcpToolSecurityAspect(
                agentVerify, debugIdentityProvider,
                new ToolSecurityPipeline(new LocalAuthorizationService(registry), permissionReview,
                        new RequestExecutionContextHolder()), agentClient, audit);
        when(agentVerify.verifySourceAndGetEmployee(any())).thenReturn(new VerifiedEmployee("tenant-1",
                new EmployeeAuth(1001L, "ENABLED", 7L, Map.of(), Map.of())));
        when(permissionReview.review(any(), any(), any(), any(), any())).thenReturn(
                com.kevin.mcp.authorization.PermissionReviewResult.allow());

        ProceedingJoinPoint first = mock(ProceedingJoinPoint.class);
        when(first.getArgs()).thenReturn(new Object[]{mock(McpSyncRequestContext.class)});
        when(first.proceed()).thenReturn(Map.of("outcome", "OK"));
        Object firstResult = aspect.guard(first, capability());
        ProceedingJoinPoint second = mock(ProceedingJoinPoint.class);
        when(second.getArgs()).thenReturn(new Object[]{mock(McpSyncRequestContext.class)});
        when(second.proceed()).thenReturn(Map.of("outcome", "OK"));
        Object secondResult = aspect.guard(second, capability());

        assertThat(firstResult).isEqualTo(Map.of("outcome", "OK"));
        assertThat(secondResult).isEqualTo(Map.of("outcome", "OK"));
        verify(permissionReview,
                org.mockito.Mockito.times(1)).review(any(), any(), any(), any(), any());
    }

    /** 构造最小公开能力声明。 */
    private PublicMcpTool capability() {
        return new PublicMcpTool() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return PublicMcpTool.class;
            }

            @Override
            public String capabilityId() {
                return "k3.metadata";
            }

            @Override
            public String description() {
                return "探索金蝶元数据";
            }
        };
    }
}



