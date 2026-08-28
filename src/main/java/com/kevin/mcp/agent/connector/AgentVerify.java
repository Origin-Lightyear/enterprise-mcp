package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.EmployeeAuth;
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
    private final String tenantId;
    private final long timestampSkewSeconds;

    /**
     * 注入请求来源校验依赖。
     *
     * @param agentSecurityCodec 安全编解码器
     * @param tenantCacheService 租户缓存服务
     * @param agentServerClient AgentServer 客户端
     * @param tenantConfigSynchronizer 租户配置同步器
     * @param tenantId 当前服务绑定的租户
     * @param timestampSkewSeconds 允许的时间戳偏差秒数
     */
    AgentVerify(
            AgentSecurityCodec agentSecurityCodec,
            TenantCacheService tenantCacheService,
            HttpAgentServerClient agentServerClient,
            TenantConfigSynchronizer tenantConfigSynchronizer,
            @Value("${saas.tenant-id}") String tenantId,
            @Value("${saas.verify.timestamp-skew-seconds:300}") long timestampSkewSeconds
    ) {
        this.agentSecurityCodec = agentSecurityCodec;
        this.tenantCacheService = tenantCacheService;
        this.agentServerClient = agentServerClient;
        this.tenantConfigSynchronizer = tenantConfigSynchronizer;
        this.tenantId = tenantId;
        this.timestampSkewSeconds = timestampSkewSeconds;
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
        // 校验时间戳是否在允许的偏差范围内，防止重放攻击
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

        // 校验租户是否匹配当前服务绑定的租户
        if (!this.tenantId.equals(requestInfo.tenantId())) {
            return null;
        }
        // 校验 nonce 是否已被使用（防重放攻击）
        if (this.tenantCacheService.isNonceUsed(requestInfo.tenantId(), nonce)) {
            return null;
        }
        // 校验请求签名，确保请求未被篡改
        if (!this.agentSecurityCodec.verifySignature(tenantHeader, timestamp, nonce, authorization)) {
            return null;
        }

        // 记录已使用的 nonce，后续相同 nonce 将被拒绝
        this.tenantCacheService.rememberNonce(requestInfo.tenantId(), nonce);
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
