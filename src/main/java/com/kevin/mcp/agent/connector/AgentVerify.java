package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.EmployeeAuth;
import com.kevin.mcp.agent.connector.entity.TenantConfig;
import com.kevin.mcp.processor.LLMProcessor;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 校验 AgentServer 请求来源，并在缓存缺失或版本变化时刷新本地租户与员工快照。
 *
 * @author Kevin
 * @date 2026-07-29
 */
@Component
public class AgentVerify {
    private static final Logger log = LoggerFactory.getLogger(AgentVerify.class);

    private final AgentSecurityCodec agentSecurityCodec;
    private final TenantCacheService tenantCacheService;
    private final HttpAgentServerClient agentServerClient;
    private final LLMProcessor llmProcessor;
    private final String tenantId;
    private final long timestampSkewSeconds;

    /**
     * 注入请求来源校验依赖。
     *
     * @param agentSecurityCodec 安全编解码器
     * @param tenantCacheService 租户缓存服务
     * @param agentServerClient AgentServer 客户端
     * @param llmProcessor LLM 处理器
     * @param tenantId 当前服务绑定的租户
     * @param timestampSkewSeconds 允许的时间戳偏差秒数
     */
    AgentVerify(
            AgentSecurityCodec agentSecurityCodec,
            TenantCacheService tenantCacheService,
            HttpAgentServerClient agentServerClient,
            LLMProcessor llmProcessor,
            @Value("${saas.tenant-id}") String tenantId,
            @Value("${saas.verify.timestamp-skew-seconds:300}") long timestampSkewSeconds
    ) {
        this.agentSecurityCodec = agentSecurityCodec;
        this.tenantCacheService = tenantCacheService;
        this.agentServerClient = agentServerClient;
        this.llmProcessor = llmProcessor;
        this.tenantId = tenantId;
        this.timestampSkewSeconds = timestampSkewSeconds;
    }

    /**
     * 校验请求来源并返回当前员工鉴权快照，供后续执行计划权限审查复用。
     *
     * @param requestContext MCP 同步请求上下文
     * @return 员工鉴权快照，校验失败时返回 null
     */
    public EmployeeAuth verifySourceAndGetAuth(McpSyncRequestContext requestContext) {
        McpTransportContext context = requestContext.transportContext();
        String authorization = this.getHeader(context, "authorization");
        String tenantHeader = this.getHeader(context, "tenant");
        String timestamp = this.getHeader(context, "timestamp");
        String nonce = this.getHeader(context, "nonce");
        if (authorization == null || tenantHeader == null || timestamp == null || nonce == null) {
            return null;
        }

        long requestTimestamp;
        try {
            requestTimestamp = Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            return null;
        }
        if (!this.agentSecurityCodec.isTimestampAllowed(requestTimestamp, this.timestampSkewSeconds)) {
            return null;
        }

        TenantRequestInfo requestInfo;
        try {
            requestInfo = this.agentSecurityCodec.parseTenantHeader(tenantHeader);
        } catch (Exception exception) {
            log.warn("parseTenantHeader failed: {}", exception.getMessage());
            return null;
        }

        if (!this.tenantId.equals(requestInfo.tenantId())) {
            return null;
        }
        if (this.tenantCacheService.isNonceUsed(requestInfo.tenantId(), nonce)) {
            return null;
        }
        if (!this.agentSecurityCodec.verifySignature(tenantHeader, timestamp, nonce, authorization)) {
            return null;
        }

        this.tenantCacheService.rememberNonce(requestInfo.tenantId(), nonce);
        long cachedVersion = this.tenantCacheService.getTenantVersion(requestInfo.tenantId());
        EmployeeAuth employeeAuth = this.tenantCacheService.getEmployeeAuth(requestInfo.tenantId(), requestInfo.employeeId());

        // 版本升级或员工缓存缺失时都要补拉鉴权快照，避免服务启动后的首个同版本请求被误拒绝。
        if (requestInfo.versionNumber() > cachedVersion || employeeAuth == null) {
            TenantConfig tenantConfig = this.agentServerClient.fetchTenantConfig(requestInfo.tenantId());
            employeeAuth = this.agentServerClient.fetchTenantAuth(requestInfo.tenantId(), requestInfo.employeeId());
            this.tenantCacheService.refreshTenantConfig(requestInfo.tenantId(), tenantConfig);
            this.tenantCacheService.refreshEmployeeAuth(requestInfo.tenantId(), employeeAuth);
            this.llmProcessor.refreshTenantConfig(tenantConfig);
        }
        if (!this.tenantCacheService.isTenantRequestAllowed(requestInfo.tenantId(), requestInfo.employeeId())) {
            return null;
        }
        return employeeAuth;
    }

    private String getHeader(McpTransportContext context, String key) {
        Object value = context.get(key);
        return value == null ? null : value.toString();
    }
}
