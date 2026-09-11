package com.kevin.mcp.resource.http;

import com.kevin.mcp.resource.configuration.ResourceProfileProperties;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对每个 HTTP 资源执行限流和熔断保护。
 *
 * @author Kevin
 * @date 2026-09-09
 */
final class HttpRequestGuard {

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /** 在请求发出前检查限流和熔断状态。 */
    void before(String resourceId, ResourceProfileProperties.Profile profile) {
        State state = states.computeIfAbsent(resourceId, key -> new State());
        synchronized (state) {
            long now = System.nanoTime();
            if (profile.getCircuitFailureThreshold() > 0 && state.openUntilNanos > now) {
                throw new IllegalStateException("HTTP circuit is open: " + resourceId);
            }
            if (profile.getRateLimitPerSecond() > 0) {
                if (now - state.windowStartNanos >= Duration.ofSeconds(1).toNanos()) {
                    state.windowStartNanos = now;
                    state.windowRequests = 0;
                }
                if (state.windowRequests >= profile.getRateLimitPerSecond()) {
                    throw new IllegalStateException("HTTP rate limit exceeded: " + resourceId);
                }
                state.windowRequests++;
            }
        }
    }

    /** 记录成功请求并关闭半开熔断。 */
    void success(String resourceId) {
        State state = states.get(resourceId);
        if (state != null) {
            synchronized (state) {
                state.failures = 0;
                state.openUntilNanos = 0;
            }
        }
    }

    /** 记录失败请求，达到阈值后打开熔断。 */
    void failure(String resourceId, ResourceProfileProperties.Profile profile) {
        if (profile.getCircuitFailureThreshold() <= 0) {
            return;
        }
        State state = states.computeIfAbsent(resourceId, key -> new State());
        synchronized (state) {
            state.failures++;
            if (state.failures >= profile.getCircuitFailureThreshold()) {
                state.openUntilNanos = System.nanoTime() + Math.max(1L,
                        profile.getCircuitOpenDuration().toNanos());
            }
        }
    }

    private static final class State {
        private long windowStartNanos = System.nanoTime();
        private int windowRequests;
        private int failures;
        private long openUntilNanos;
    }
}
