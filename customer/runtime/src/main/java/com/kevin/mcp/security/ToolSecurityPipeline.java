package com.kevin.mcp.security;

import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.identity.VerifiedEmployee;
import com.kevin.mcp.annotation.PublicMcpTool;
import com.kevin.mcp.audit.ExecutionAuditService;
import com.kevin.mcp.authorization.LocalAuthorizationService;
import com.kevin.mcp.execution.RequestExecutionContext;
import com.kevin.mcp.execution.RequestExecutionContextHolder;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 统一公开与内部 MCP 工具的单次调用安全管线。
 *
 * <p>身份校验、能力授权、限流、审计和请求上下文绑定只有这一份实现；
 * 公开工具经 AOP 进入，内部计划经 CommonInternalTool 进入。任何新增调用入口
 * 必须复用本组件，禁止为公开工具另建第二套验证、权限或审计流程。</p>
 *
 * @author Kevin
 * @date 2026-10-16
 */
@Component
public class ToolSecurityPipeline {

    private static final int MAX_CALLS_PER_MINUTE = 30;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final LocalAuthorizationService localAuthorization;
    private final RequestExecutionContextHolder executionContextHolder;

    /**
     * 注入共享授权与上下文组件。
     *
     * @param localAuthorization 本地只读能力校验
     * @param executionContextHolder 请求上下文持有器
     */
    public ToolSecurityPipeline(LocalAuthorizationService localAuthorization,
                                RequestExecutionContextHolder executionContextHolder) {
        this.localAuthorization = localAuthorization;
        this.executionContextHolder = executionContextHolder;
    }

    /**
     * 执行公开工具完整安全流程：授权、限流、审计、绑定上下文并执行工具。
     *
     * @param requestContext MCP 请求上下文
     * @param employee 已验证员工身份
     * @param debugBypass 调试旁路标志
     * @param capability 公开工具能力声明
     * @param toolCall 工具本体调用
     * @param fetchLlmConfig 兼容公开工具切面调用契约的员工模型配置加载器
     * @param audit 本地审计服务
     * @return 工具结果或结构化限流/拒绝提示
     * @throws Exception 工具执行或审计异常
     */
    public Object executePublicTool(McpSyncRequestContext requestContext, VerifiedEmployee employee,
                                    boolean debugBypass, PublicMcpTool capability, List<Object> requestedArguments,
                                    Callable<Object> toolCall, Supplier<Object> fetchLlmConfig,
                                    ExecutionAuditService audit) throws Exception {
        String tenantId = employee.tenantId();
        Long employeeId = employee.employeeAuth().employeeId();
        String paramsDigest = ToolSecurityPipelineDigest.sha256(requestedArguments.toString());
        localAuthorization.authorizePublicCapability(capability);
        String rateKey = tenantId + ':' + employeeId + ':' + capability.capabilityId();
        if (!allow(rateKey)) {
            if (!debugBypass) {
                audit.recordPublicToolCall(tenantId, employeeId, capability.capabilityId(),
                        "deny", paramsDigest, "RATE_LIMITED");
            }
            return Map.of("outcome", "RATE_LIMITED", "message", "探索频率超限，请稍后再试");
        }
        RequestExecutionContext context = new RequestExecutionContext(null, "", tenantId, employeeId,
                employee.employeeAuth().permissionConfig());
        try (RequestExecutionContextHolder.Scope ignored = executionContextHolder.bind(context)) {
            Object result = toolCall.call();
            if (!debugBypass) {
                audit.recordPublicToolCall(tenantId, employeeId, capability.capabilityId(),
                        "allow", paramsDigest, "SUCCEEDED");
            }
            return result;
        } catch (Exception failure) {
            if (!debugBypass) {
                audit.recordPublicToolCall(tenantId, employeeId, capability.capabilityId(),
                        "allow", paramsDigest, "FAILED");
            }
            throw failure;
        }
    }

    /**
     * 清理已经过期的限流窗口，防止长期运行积累员工键。
     */
    public void evictExpiredWindows() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().minute() < now / 60_000 - 1);
    }

    private boolean allow(String key) {
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(key, (ignored, old) ->
                old == null || old.minute() != minute ? new Window(minute, 1)
                        : new Window(minute, old.count() + 1));
        return window.count() <= MAX_CALLS_PER_MINUTE;
    }

    private record Window(long minute, int count) {
    }

    /**
     * 计算调用参数的 SHA-256 摘要，供审计记录使用。
     *
     * @param value 参数文本
     * @return SHA-256 十六进制摘要
     */
    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 保持摘要引用一致的私有占位，无实例状态。 */
    private static final class ToolSecurityPipelineDigest {

        private ToolSecurityPipelineDigest() {
        }

        /**
         * 计算参数摘要，供审计记录使用。
         *
         * @param value 参数文本
         * @return SHA-256 十六进制摘要
         */
        static String sha256(String value) {
            return digest(value);
        }
    }

}





