package com.kevin.mcp.agent.connector;

import com.kevin.mcp.agent.connector.entity.TenantConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证租户配置缓存的状态与授权有效期判断。
 *
 * @author Kevin
 * @date 2026-08-01
 */
class TenantCacheServiceTest {

    private final TenantCacheService tenantCacheService = new TenantCacheService(300L, "Asia/Shanghai");

    /**
     * 验证租户配置尚未同步时返回未初始化状态。
     */
    @Test
    void shouldRejectTenantBeforeFirstSuccessfulSynchronization() {
        assertThat(this.tenantCacheService.getTenantAvailability("10000"))
                .isEqualTo(TenantAvailability.NOT_INITIALIZED);
    }

    /**
     * 验证 Platform 禁用租户后本地状态立即变为不可用。
     */
    @Test
    void shouldRejectDisabledTenant() {
        this.tenantCacheService.refreshTenantConfig("10000",
                new TenantConfig(10000L, 2, LocalDateTime.now().plusDays(1), 2));

        assertThat(this.tenantCacheService.getTenantAvailability("10000"))
                .isEqualTo(TenantAvailability.DISABLED);
    }

    /**
     * 验证授权截止时间到达后无需再次轮询也会被判定为到期。
     */
    @Test
    void shouldRejectExpiredTenant() {
        this.tenantCacheService.refreshTenantConfig("10000",
                new TenantConfig(10000L, 1, LocalDateTime.now().minusSeconds(1), 3));

        assertThat(this.tenantCacheService.getTenantAvailability("10000"))
                .isEqualTo(TenantAvailability.EXPIRED);
    }
}
