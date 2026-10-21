package com.kevin.mcp.cloud.employee;

import com.kevin.mcp.cloud.configuration.ControlPlanePlanningProperties;
import com.kevin.mcp.llm.LlmModelSelection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 获取并缓存员工专属 LLM 调用配置，按租户和员工双键隔离敏感凭证。
 *
 * @author Kevin
 * @date 2026-09-10
 */
@Service
public class EmployeeLlmConfigService {

    private static final int LOCK_STRIPE_COUNT = 64;

    private final EmployeeLlmConfigClient configClient;
    private final Duration cacheTtl;
    private final int maximumSize;
    private final Clock clock;
    private final Map<CacheKey, CacheEntry> configCache = new ConcurrentHashMap<>();
    private final Object[] loadLocks = new Object[LOCK_STRIPE_COUNT];
    private final Object capacityLock = new Object();

    /**
     * 使用 Control Plane 配置初始化员工 LLM 配置缓存。
     *
     * @param configClient Tenant 和 NewAPI 配置客户端
     * @param properties Control Plane 规划配置
     */
    @Autowired
    public EmployeeLlmConfigService(EmployeeLlmConfigClient configClient,
                                    ControlPlanePlanningProperties properties) {
        this(configClient, properties.getEmployeeLlmConfigCacheTtl(),
                properties.getEmployeeLlmConfigCacheMaximumSize(), Clock.systemUTC());
    }

    /**
     * 使用指定时钟初始化缓存，供确定性测试验证硬过期边界。
     *
     * @param configClient Tenant 和 NewAPI 配置客户端
     * @param cacheTtl 缓存有效期
     * @param maximumSize 缓存最大员工数
     * @param clock 缓存时间源
     */
    EmployeeLlmConfigService(EmployeeLlmConfigClient configClient, Duration cacheTtl,
                             int maximumSize, Clock clock) {
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("Employee LLM config cache TTL must be positive");
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("Employee LLM config cache maximum size must be positive");
        }
        this.configClient = Objects.requireNonNull(configClient, "Employee LLM config client is required");
        this.cacheTtl = cacheTtl;
        this.maximumSize = maximumSize;
        this.clock = Objects.requireNonNull(clock, "Employee LLM config cache clock is required");
        for (int index = 0; index < this.loadLocks.length; index++) {
            this.loadLocks[index] = new Object();
        }
    }

    /**
     * 返回员工当前可用的 LLM 调用配置；硬过期后同步刷新，失败时拒绝沿用旧密钥。
     *
     * @param tenantId 租户标识
     * @param employeeId 员工编号
     * @return 已按 endpoint 能力解析模型角色的员工 LLM 调用配置
     * @throws IOException Tenant 或 NewAPI 调用失败
     */
    public EmployeeLlmInvocationConfig getConfig(String tenantId, Long employeeId) throws IOException {
        CacheKey cacheKey = buildCacheKey(tenantId, employeeId);
        Instant now = clock.instant();
        CacheEntry cachedEntry = configCache.get(cacheKey);
        if (cachedEntry != null && cachedEntry.expiresAt().isAfter(now)) {
            return cachedEntry.config();
        }

        Object loadLock = loadLocks[Math.floorMod(cacheKey.hashCode(), loadLocks.length)];
        synchronized (loadLock) {
            now = clock.instant();
            cachedEntry = configCache.get(cacheKey);
            if (cachedEntry != null && cachedEntry.expiresAt().isAfter(now)) {
                return cachedEntry.config();
            }

            EmployeeLlmConfig employeeConfig = configClient.fetchConfig(cacheKey.tenantId(), cacheKey.employeeId());
            LlmModelSelection models = configClient.resolveModels(employeeConfig);
            EmployeeLlmInvocationConfig invocationConfig = new EmployeeLlmInvocationConfig(
                    employeeConfig.newapiUrl().trim(), employeeConfig.apiKey().trim(), models.generationModel(),
                    models.embeddingModel(), models.rerankModel());
            synchronized (capacityLock) {
                removeExpiredEntries(now);
                ensureCapacity(cacheKey);
                configCache.put(cacheKey, new CacheEntry(invocationConfig, clock.instant().plus(cacheTtl)));
            }
            return invocationConfig;
        }
    }

    private void removeExpiredEntries(Instant now) {
        configCache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void ensureCapacity(CacheKey incomingKey) {
        if (configCache.containsKey(incomingKey) || configCache.size() < maximumSize) {
            return;
        }
        configCache.entrySet().stream()
                .min(Map.Entry.comparingByValue((left, right) -> left.expiresAt().compareTo(right.expiresAt())))
                .ifPresent(entry -> configCache.remove(entry.getKey(), entry.getValue()));
    }

    private CacheKey buildCacheKey(String tenantId, Long employeeId) {
        if (isBlank(tenantId) || employeeId == null || employeeId <= 0) {
            throw new IllegalArgumentException("Tenant ID and employee ID are required for employee LLM config");
        }
        return new CacheKey(tenantId.trim(), employeeId);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 定义租户与员工复合缓存键，避免字符串拼接歧义和跨租户串用配置。
     *
     * @param tenantId 租户标识
     * @param employeeId 员工编号
     * @author Kevin
     * @date 2026-09-10
     */
    private record CacheKey(String tenantId, Long employeeId) {
    }

    /**
     * 保存缓存值及硬过期时间，过期后不使用旧配置兜底。
     *
     * @param config 员工 LLM 调用配置
     * @param expiresAt 硬过期时间
     * @author Kevin
     * @date 2026-09-10
     */
    private record CacheEntry(EmployeeLlmInvocationConfig config, Instant expiresAt) {
    }
}