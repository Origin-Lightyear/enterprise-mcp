package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.EmployeeAuth;
import com.kevin.mcp.agent.connector.entity.TenantConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理租户配置、员工鉴权和随机数防重放缓存。
 *
 * @author Kevin
 * @date 2026-07-29
 */
@Component
public class TenantCacheService {
    private final long nonceTtlSeconds;
    private final ZoneId tenantZoneId;
    private final Map<String, TenantConfig> tenantConfigCache = new ConcurrentHashMap<>();
    private final Map<String, EmployeeAuth> employeeAuthCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> nonceCache = new ConcurrentHashMap<>();

    /**
     * 使用配置的随机数 TTL 初始化缓存服务。
     *
     * @param nonceTtlSeconds 随机数去重窗口，单位秒
     */
    public TenantCacheService(
            @Value("${saas.verify.nonce-ttl-seconds:300}") long nonceTtlSeconds,
            @Value("${saas.tenant-zone:Asia/Shanghai}") String tenantZone
    ) {
        this.nonceTtlSeconds = nonceTtlSeconds;
        this.tenantZoneId = ZoneId.of(tenantZone);
    }

    /**
     * 返回当前缓存中的租户版本号，未命中时视为 0。
     *
     * @param tenantId 租户标识
     * @return 本地缓存版本号
     */
    public long getTenantVersion(String tenantId) {
        TenantConfig tenantConfig = this.tenantConfigCache.get(tenantId);
        return tenantConfig == null || tenantConfig.version() == null ? 0L : tenantConfig.version().longValue();
    }

    /**
     * 读取当前租户配置快照，供同步器比较 Platform 配置是否发生变化。
     *
     * @param tenantId 租户标识
     * @return 租户配置快照，尚未同步时返回 null
     */
    public TenantConfig getTenantConfig(String tenantId) {
        return this.tenantConfigCache.get(tenantId);
    }

    /**
     * 判断随机数是否在有效窗口内重复出现，避免重放请求再次进入业务层。
     *
     * @param tenantId 租户标识
     * @param nonce 请求随机数
     * @return 是否已使用
     */
    public boolean isNonceUsed(String tenantId, String nonce) {
        this.clearExpiredNonce();
        Instant expiresAt = this.nonceCache.get(this.buildNonceKey(tenantId, nonce));
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    /**
     * 记录已通过基础校验的随机数，缩小同一时间窗内被重放的机会。
     *
     * @param tenantId 租户标识
     * @param nonce 请求随机数
     */
    public void rememberNonce(String tenantId, String nonce) {
        this.clearExpiredNonce();
        this.nonceCache.put(this.buildNonceKey(tenantId, nonce), Instant.now().plusSeconds(this.nonceTtlSeconds));
    }

    /**
     * 使用显式租户标识刷新租户配置，避免远端模型未返回租户键时无法落缓存。
     *
     * @param tenantId 租户标识
     * @param tenantConfig 最新租户配置
     */
    public void refreshTenantConfig(String tenantId, TenantConfig tenantConfig) {
        if (tenantConfig != null) {
            this.tenantConfigCache.put(tenantId, tenantConfig);
        }
    }

    /**
     * 刷新员工鉴权缓存，使用租户和员工双键避免跨租户员工编号互相覆盖。
     *
     * @param tenantId 租户标识
     * @param employeeAuth 最新员工鉴权
     */
    public void refreshEmployeeAuth(String tenantId, EmployeeAuth employeeAuth) {
        if (employeeAuth != null) {
            this.employeeAuthCache.put(this.buildEmployeeKey(tenantId, employeeAuth.employeeId()), employeeAuth);
        }
    }

    /**
     * 综合租户状态、授权截止时间和员工状态判断当前请求是否允许继续执行。
     *
     * @param tenantId 租户标识
     * @param employeeId 员工标识
     * @return 是否允许当前请求
     */
    public boolean isTenantRequestAllowed(String tenantId, String employeeId) {
        EmployeeAuth employeeAuth = this.employeeAuthCache.get(this.buildEmployeeKey(tenantId, employeeId));
        if (TenantAvailability.AVAILABLE != this.getTenantAvailability(tenantId) || employeeAuth == null) {
            return false;
        }
        return "ENABLED".equalsIgnoreCase(employeeAuth.status());
    }

    /**
     * 判断租户当前是否可用，让禁用和到期规则在进入 MCP 协议处理前即可生效。
     * 授权截止时间按 Platform 所在业务时区解释，并在截止时刻立即视为到期。
     *
     * @param tenantId 租户标识
     * @return 当前租户可用状态
     */
    public TenantAvailability getTenantAvailability(String tenantId) {
        TenantConfig tenantConfig = this.tenantConfigCache.get(tenantId);
        if (tenantConfig == null) {
            return TenantAvailability.NOT_INITIALIZED;
        }
        if (!Integer.valueOf(1).equals(tenantConfig.status())) {
            return TenantAvailability.DISABLED;
        }
        LocalDateTime authEndTime = tenantConfig.authEndTime();
        if (authEndTime != null && !authEndTime.atZone(this.tenantZoneId).toInstant().isAfter(Instant.now())) {
            return TenantAvailability.EXPIRED;
        }
        return TenantAvailability.AVAILABLE;
    }

    /**
     * 读取员工鉴权快照，供权限审查复用已刷新的有效规则。
     *
     * @param tenantId 租户标识
     * @param employeeId 员工标识
     * @return 员工鉴权快照，未命中时返回 null
     */
    public EmployeeAuth getEmployeeAuth(String tenantId, String employeeId) {
        return this.employeeAuthCache.get(this.buildEmployeeKey(tenantId, employeeId));
    }

    private void clearExpiredNonce() {
        Instant now = Instant.now();
        this.nonceCache.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
    }

    private String buildNonceKey(String tenantId, String nonce) {
        return tenantId + ":" + nonce;
    }

    private String buildEmployeeKey(String tenantId, String employeeId) {
        return tenantId + ":" + employeeId;
    }
}
