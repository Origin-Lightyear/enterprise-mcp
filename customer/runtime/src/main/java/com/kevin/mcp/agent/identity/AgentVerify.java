package com.kevin.mcp.agent.identity;

import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.agent.model.EmployeeAuth;
import com.kevin.mcp.agent.service.TenantCacheService;
import com.kevin.mcp.agent.service.TenantConfigSynchronizer;
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
    private final TenantConfigSynchronizer tenantConfigSynchronizer;
    private final RuntimeIdentityProperties runtimeIdentityProperties;
    private final long timestampSkewSeconds;
    private final long credentialMaxTtlSeconds;

    /**
     * 注入请求来源校验依赖。
     *
     * @param agentSecurityCodec 安全编解码器
     * @param tenantCacheService 租户缓存服务
     * @param agentServerClient AgentServer 客户端
     * @param tenantConfigSynchronizer 租户配置同步器
     * @param runtimeIdentityProperties 当前 Runtime 的租户与实例身份
     * @param timestampSkewSeconds 允许的时间戳偏差秒数
     * @param credentialMaxTtlSeconds 一次性凭证最长生命周期秒数
     */
    AgentVerify(
            AgentSecurityCodec agentSecurityCodec,
            TenantCacheService tenantCacheService,
            HttpAgentServerClient agentServerClient,
            TenantConfigSynchronizer tenantConfigSynchronizer,
            RuntimeIdentityProperties runtimeIdentityProperties,
            @Value("${saas.verify.timestamp-skew-seconds:300}") long timestampSkewSeconds,
            @Value("${saas.verify.credential-max-ttl-seconds:60}") long credentialMaxTtlSeconds
    ) {
        this.agentSecurityCodec = agentSecurityCodec;
        this.tenantCacheService = tenantCacheService;
        this.agentServerClient = agentServerClient;
        this.tenantConfigSynchronizer = tenantConfigSynchronizer;
        this.runtimeIdentityProperties = runtimeIdentityProperties;
        this.timestampSkewSeconds = timestampSkewSeconds;
        this.credentialMaxTtlSeconds = credentialMaxTtlSeconds;
    }

    /**
     * 校验请求来源并返回已验证的租户与员工身份，供 LLM 配置和权限审查复用。
     *
     * @param requestContext MCP 同步请求上下文
     * @return 已验证的员工身份，校验失败时返回 null
     */
    public VerifiedEmployee verifySourceAndGetEmployee(McpSyncRequestContext requestContext) {
        McpTransportContext context = requestContext.transportContext();
        // 提取请求头：签名凭证、租户标识、时间戳、随机数（防重放）
        String authorization = this.getHeader(context, "authorization");
        String tenantHeader = this.getHeader(context, "tenant");
        String mcpInstanceId = this.getHeader(context, "mcpInstanceId");
        String timestamp = this.getHeader(context, "timestamp");
        String expiresAt = this.getHeader(context, "expiresAt");
        String nonce = this.getHeader(context, "nonce");
        if (authorization == null || tenantHeader == null || mcpInstanceId == null || timestamp == null
                || expiresAt == null || nonce == null) {
            return null;
        }

        long requestTimestamp;
        long credentialExpiresAt;
        try {
            requestTimestamp = Long.parseLong(timestamp);
            credentialExpiresAt = Long.parseLong(expiresAt);
        } catch (NumberFormatException exception) {
            return null;
        }
        // 校验时间戳是否在允许的偏差范围内，防止重放攻击
        if (!this.agentSecurityCodec.isTimestampAllowed(requestTimestamp, this.timestampSkewSeconds)
                || !this.agentSecurityCodec.isCredentialTimeAllowed(requestTimestamp, credentialExpiresAt,
                this.credentialMaxTtlSeconds)) {
            return null;
        }

        TenantRequestInfo requestInfo;
        try {
            requestInfo = this.agentSecurityCodec.parseTenantHeader(tenantHeader);
        } catch (Exception exception) {
            log.warn("parseTenantHeader failed: {}", exception.getMessage());
            return null;
        }

        // 校验租户是否匹配当前服务绑定的租户
        if (!this.runtimeIdentityProperties.getTenantId().equals(requestInfo.tenantId())
                || !this.runtimeIdentityProperties.getInstanceId().equals(requestInfo.mcpInstanceId())
                || !this.runtimeIdentityProperties.getInstanceId().equals(mcpInstanceId)) {
            return null;
        }
        // 校验请求签名，确保请求未被篡改
        if (!this.agentSecurityCodec.verifySignature(tenantHeader, mcpInstanceId, timestamp, expiresAt, nonce,
                authorization)) {
            return null;
        }

        // 验签成功后原子占用 nonce，避免无效请求消耗 nonce，也关闭并发重放窗口。
        if (!this.tenantCacheService.rememberNonceIfAbsent(requestInfo.tenantId(), nonce)) {
            return null;
        }
        long cachedVersion = this.tenantCacheService.getTenantVersion(requestInfo.tenantId());
        EmployeeAuth employeeAuth = this.tenantCacheService.getEmployeeAuth(requestInfo.tenantId(), requestInfo.employeeId());

        // 远端版本号比本地缓存新，或员工鉴权信息在缓存中不存在时，
        // 从 AgentServer 重新拉取租户配置和员工鉴权快照并刷新本地缓存。
        // 这避免了服务刚启动或版本升级后首个请求因缓存未命中而被误拒绝。
        if (requestInfo.versionNumber() > cachedVersion || employeeAuth == null) {
            if (requestInfo.versionNumber() > cachedVersion) {
                // 已知 Platform 存在新版本却无法同步时失败关闭，避免租户禁用或到期后继续按旧缓存放行。
                if (!this.tenantConfigSynchronizer.synchronizeSafely()) {
                    return null;
                }
            }
            employeeAuth = this.agentServerClient.fetchTenantAuth(requestInfo.tenantId(), requestInfo.employeeId());
            this.tenantCacheService.refreshEmployeeAuth(requestInfo.tenantId(), employeeAuth);
        }
        // 最终权限检查：判断该员工在当前租户下是否被允许发起请求
        if (!this.tenantCacheService.isTenantRequestAllowed(requestInfo.tenantId(), requestInfo.employeeId())) {
            return null;
        }
        return new VerifiedEmployee(requestInfo.tenantId(), employeeAuth);
    }

    private String getHeader(McpTransportContext context, String key) {
        Object value = context.get(key);
        return value == null ? null : value.toString();
    }
}
