package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.EmployeeAuth;
import com.kevin.mcp.agent.connector.entity.TenantConfig;
import io.modelcontextprotocol.common.McpTransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 校验 AgentServer 请求来源，并在版本变化时刷新本地租户缓存。
 */
@Component
public class AgentVerify {
    private static final Logger log = LoggerFactory.getLogger(AgentVerify.class);

    private final AgentSecurityCodec agentSecurityCodec;
    private final TenantCacheService tenantCacheService;
    private final HttpAgentServerClient agentServerClient;
    private final String tenantId;
    private final long timestampSkewSeconds;

    /**
     * 初始化请求来源校验器。
     *
     * @param agentSecurityCodec 安全编解码器
     * @param tenantCacheService 租户缓存服务
     * @param agentServerClient AgentServer 客户端
     * @param tenantId 当前服务绑定的租户
     * @param timestampSkewSeconds 允许的时间戳偏差秒数
     */
    AgentVerify(
            AgentSecurityCodec agentSecurityCodec,
            TenantCacheService tenantCacheService,
            HttpAgentServerClient agentServerClient,
            @Value("${agent.tenant-id}") String tenantId,
            @Value("${agent.verify.timestamp-skew-seconds:300}") long timestampSkewSeconds
    ) {
        this.agentSecurityCodec = agentSecurityCodec;
        this.tenantCacheService = tenantCacheService;
        this.agentServerClient = agentServerClient;
        this.tenantId = tenantId;
        this.timestampSkewSeconds = timestampSkewSeconds;
    }

    /**
     * 校验请求头中的签名、租户上下文与版本信息，只允许固定 AgentServer 调用。
     *
     * @param requestContext MCP 同步请求上下文
     * @return 是否允许继续执行业务工具
     */
    public boolean verifySource(McpSyncRequestContext requestContext) {
        McpTransportContext context = requestContext.transportContext();
        String authorization = getHeader(context, "authorization");
        String tenantHeader = getHeader(context, "tenant");
        String timestamp = getHeader(context, "timestamp");
        String nonce = getHeader(context, "nonce");
        if (authorization == null || tenantHeader == null || timestamp == null || nonce == null) {
            return false;
        }

        long requestTimestamp;
        try {
            requestTimestamp = Long.parseLong(timestamp);
        } catch (NumberFormatException exception) {
            return false;
        }
        if (!this.agentSecurityCodec.isTimestampAllowed(requestTimestamp, this.timestampSkewSeconds)) {
            return false;
        }

        TenantRequestInfo requestInfo;
        try {
            requestInfo = this.agentSecurityCodec.parseTenantHeader(tenantHeader);
        } catch (Exception exception) {
            log.warn("parseTenantHeader failed: {}", exception.getMessage());
            return false;
        }

        if (!this.tenantId.equals(requestInfo.tenantId())) {
            return false;
        }
        if (this.tenantCacheService.isNonceUsed(requestInfo.tenantId(), nonce)) {
            return false;
        }
        if (!this.agentSecurityCodec.verifySignature(tenantHeader, timestamp, nonce, authorization)) {
            return false;
        }

        this.tenantCacheService.rememberNonce(requestInfo.tenantId(), nonce);
        long cachedVersion = this.tenantCacheService.getTenantVersion(requestInfo.tenantId());
        if (requestInfo.versionNumber() > cachedVersion) {
            TenantConfig tenantConfig = this.agentServerClient.fetchTenantConfig(requestInfo.tenantId());
            EmployeeAuth employeeAuth = this.agentServerClient.fetchTenantAuth(requestInfo.tenantId(), requestInfo.employeeId());
            this.tenantCacheService.refreshTenantConfig(requestInfo.tenantId(), tenantConfig);
            this.tenantCacheService.refreshEmployeeAuth(requestInfo.tenantId(), employeeAuth);
        }
        return this.tenantCacheService.isTenantRequestAllowed(requestInfo.tenantId(), requestInfo.employeeId());
    }

    private String getHeader(McpTransportContext context, String key) {
        Object value = context.get(key);
        return value == null ? null : value.toString();
    }
}
