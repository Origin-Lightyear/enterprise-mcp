package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.EmployeeLlmConfig;
import com.kevin.mcp.processor.LLMProcessor;
import com.kevin.mcp.processor.LlmInvocationConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 * @date 2026-08-10
 */
@Service
public class EmployeeLlmConfigService {

    private static final int LOCK_STRIPE_COUNT = 64;

    private final HttpAgentServerClient agentServerClient;
    private final LLMProcessor llmProcessor;
    private final Duration cacheTtl;
    private final int maximumSize;
    private final Clock clock;
    private final Map<CacheKey, CacheEntry> configCache = new ConcurrentHashMap<>();
    private final Object[] loadLocks = new Object[LOCK_STRIPE_COUNT];
    private final Object capacityLock = new Object();

    /**
     * 使用可配置的 TTL 和容量上限初始化员工 LLM 配置缓存。
     *
     * @param agentServerClient Platform HTTP 客户端
     * @param llmProcessor LLM 处理器
     * @param cacheTtl 缓存有效期
     * @param maximumSize 缓存最大员工数
     */
    @Autowired
    public EmployeeLlmConfigService(
            HttpAgentServerClient agentServerClient,
            LLMProcessor llmProcessor,
            @Value("${saas.employee-llm-config-cache.ttl:5m}") Duration cacheTtl,
            @Value("${saas.employee-llm-config-cache.maximum-size:10000}") int maximumSize
    ) {
        this(agentServerClient, llmProcessor, cacheTtl, maximumSize, Clock.systemUTC());
    }

    /**
     * 使用指定时钟初始化缓存，供确定性测试验证硬过期边界。
     *
     * @param agentServerClient Platform HTTP 客户端
     * @param llmProcessor LLM 处理器
     * @param cacheTtl 缓存有效期
     * @param maximumSize 缓存最大员工数
     * @param clock 缓存时间源
     */
    EmployeeLlmConfigService(HttpAgentServerClient agentServerClient, LLMProcessor llmProcessor,
                             Duration cacheTtl, int maximumSize, Clock clock) {
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("Employee LLM config cache TTL must be positive");
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("Employee LLM config cache maximum size must be positive");
        }
        this.agentServerClient = agentServerClient;
        this.llmProcessor = llmProcessor;
        this.cacheTtl = cacheTtl;
        this.maximumSize = maximumSize;
        this.clock = Objects.requireNonNull(clock, "Employee LLM config cache clock is required");
        for (int index = 0; index < this.loadLocks.length; index++) {
            this.loadLocks[index] = new Object();
        }
    }

    /**
     * 返回员工当前可用的 LLM 调用配置；过期时同步刷新，刷新失败则拒绝沿用旧密钥。
     * 固定数量的分段锁只合并同一分段内的并发加载，避免为每位员工永久保留锁对象。
     *
     * @param tenantId 租户标识
     * @param employeeId 员工标识
     * @return 已解析默认模型的员工 LLM 调用配置
     */
    public LlmInvocationConfig getConfig(String tenantId, String employeeId) {
        CacheKey cacheKey = this.buildCacheKey(tenantId, employeeId);
        Instant now = this.clock.instant();
        CacheEntry cachedEntry = this.configCache.get(cacheKey);
        if (cachedEntry != null && cachedEntry.expiresAt().isAfter(now)) {
            return cachedEntry.config();
        }

        Object loadLock = this.loadLocks[Math.floorMod(cacheKey.hashCode(), this.loadLocks.length)];
        synchronized (loadLock) {
            now = this.clock.instant();
            cachedEntry = this.configCache.get(cacheKey);
            if (cachedEntry != null && cachedEntry.expiresAt().isAfter(now)) {
                return cachedEntry.config();
            }

            EmployeeLlmConfig employeeConfig = this.agentServerClient.fetchEmployeeLlmConfig(tenantId, employeeId);
            LlmInvocationConfig invocationConfig = this.llmProcessor.prepareInvocationConfig(employeeConfig);
            synchronized (this.capacityLock) {
                this.removeExpiredEntries(now);
                this.ensureCapacity(cacheKey);
                this.configCache.put(cacheKey, new CacheEntry(invocationConfig, this.clock.instant().plus(this.cacheTtl)));
            }
            return invocationConfig;
        }
    }

    private void removeExpiredEntries(Instant now) {
        this.configCache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void ensureCapacity(CacheKey incomingKey) {
        if (this.configCache.containsKey(incomingKey) || this.configCache.size() < this.maximumSize) {
            return;
        }
        this.configCache.entrySet().stream()
                .min(Map.Entry.comparingByValue((left, right) -> left.expiresAt().compareTo(right.expiresAt())))
                .ifPresent(entry -> this.configCache.remove(entry.getKey(), entry.getValue()));
    }

    private CacheKey buildCacheKey(String tenantId, String employeeId) {
        if (tenantId == null || tenantId.isBlank() || employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("Tenant ID and employee ID are required for employee LLM config");
        }
        return new CacheKey(tenantId.trim(), employeeId.trim());
    }

    /**
     * 定义租户与员工复合缓存键，避免字符串拼接产生歧义。
     *
     * @param tenantId 租户标识
     * @param employeeId 员工标识
     * @author Kevin
     * @date 2026-08-10
     */
    private record CacheKey(String tenantId, String employeeId) {
    }

    /**
     * 保存缓存值及其硬过期时间，过期后不使用旧配置兜底。
     *
     * @param config 员工 LLM 调用配置
     * @param expiresAt 硬过期时间
     * @author Kevin
     * @date 2026-08-10
     */
    private record CacheEntry(LlmInvocationConfig config, Instant expiresAt) {
    }
}
