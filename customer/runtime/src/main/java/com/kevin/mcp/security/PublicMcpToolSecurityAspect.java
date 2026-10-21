package com.kevin.mcp.security;

import com.kevin.mcp.agent.identity.AgentVerify;
import com.kevin.mcp.agent.identity.DebugIdentityProvider;
import com.kevin.mcp.agent.identity.VerifiedEmployee;
import com.kevin.mcp.annotation.PublicMcpTool;
import com.kevin.mcp.audit.ExecutionAuditService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 让公开工具复用 Runtime 统一安全管线的 AOP 入口。
 *
 * <p>公开只表示 Agent 可直接发现和调用，绝不表示匿名或免权限。
 * 身份校验、授权、限流、审查、审计与请求上下文绑定全部委托
 * {@link ToolSecurityPipeline}，本切面只负责解析调用参数。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Aspect
@Component
public class PublicMcpToolSecurityAspect {

    private final AgentVerify agentVerify;
    private final DebugIdentityProvider debugIdentityProvider;
    private final ToolSecurityPipeline pipeline;
    private final ExecutionAuditService audit;
    private final com.kevin.mcp.agent.client.HttpAgentServerClient agentServerClient;

    /**
     * 注入身份校验和统一安全管线。
     *
     * @param agentVerify Agent 身份校验
     * @param debugIdentityProvider 调试旁路身份提供器
     * @param pipeline 公开与内部工具共用的安全管线
     * @param agentServerClient 员工模型配置来源
     * @param audit 本地审计
     */
    public PublicMcpToolSecurityAspect(AgentVerify agentVerify,
                                       DebugIdentityProvider debugIdentityProvider,
                                       ToolSecurityPipeline pipeline,
                                       com.kevin.mcp.agent.client.HttpAgentServerClient agentServerClient,
                                       ExecutionAuditService audit) {
        this.agentVerify = agentVerify;
        this.debugIdentityProvider = debugIdentityProvider;
        this.pipeline = pipeline;
        this.agentServerClient = agentServerClient;
        this.audit = audit;
    }

    /**
     * 在公开工具执行前委托统一安全管线完成审查与审计。
     *
     * @param invocation 被拦截的公开工具调用
     * @param capability 工具能力声明
     * @return 工具结果或结构化限流提示
     * @throws Throwable 工具执行或审计失败
     */
    @Around("@annotation(capability)")
    public Object guard(ProceedingJoinPoint invocation, PublicMcpTool capability) throws Throwable {
        McpSyncRequestContext requestContext = Arrays.stream(invocation.getArgs())
                .filter(McpSyncRequestContext.class::isInstance)
                .map(McpSyncRequestContext.class::cast)
                .findFirst()
                .orElseThrow(() -> new SecurityException("调用受限：请求缺少有效的安全上下文"));
        VerifiedEmployee employee;
        boolean debugBypass = debugIdentityProvider.isEnabled();
        if (debugBypass) {
            employee = debugIdentityProvider.loadVerifiedEmployee();
        } else {
            employee = agentVerify.verifySourceAndGetEmployee(requestContext);
            if (employee == null) {
                throw new SecurityException("调用受限：身份校验未通过");
            }
        }
        List<Object> requestedArguments = Arrays.stream(invocation.getArgs())
                .filter(arg -> !(arg instanceof McpSyncRequestContext)).toList();
        try {
            return pipeline.executePublicTool(requestContext, employee, debugBypass, capability,
                    requestedArguments, () -> {
                        try {
                            return invocation.proceed();
                        } catch (java.lang.Throwable failure) {
                            if (failure instanceof RuntimeException runtime) {
                                throw runtime;
                            }
                            if (failure instanceof java.lang.Error error) {
                                throw error;
                            }
                            throw new IllegalStateException("Public tool invocation failed", failure);
                        }
                    },
                    () -> agentServerClient.fetchEmployeeLlmConfig(employee.tenantId(),
                            employee.employeeAuth().employeeId()),
                    audit);
        } catch (java.lang.Exception failure) {
            throw failure;
        } catch (Throwable failure) {
            throw failure;
        }
    }

    /**
     * 清理已经过期的限流窗口，防止长期运行积累员工键。
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpiredWindows() {
        pipeline.evictExpiredWindows();
    }
}





