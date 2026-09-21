package com.kevin.mcp.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Runtime 对一次性 Nonce 的原子占用语义。
 *
 * @author Kevin
 * @date 2026-09-19
 */
class TenantCacheServiceTest {

    @Test
    void shouldAllowNonceOnlyOnceWithinReplayWindow() {
        TenantCacheService service = new TenantCacheService(300L, "Asia/Shanghai");

        assertThat(service.rememberNonceIfAbsent("tenant-1", "nonce-1")).isTrue();
        assertThat(service.rememberNonceIfAbsent("tenant-1", "nonce-1")).isFalse();
        assertThat(service.rememberNonceIfAbsent("tenant-2", "nonce-1")).isTrue();
    }
}
