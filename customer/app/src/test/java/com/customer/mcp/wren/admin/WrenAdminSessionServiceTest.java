package com.customer.mcp.wren.admin;

import com.customer.mcp.wren.WrenProperties;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 Wren 管理 Session 的认证、随机性与过期边界。
 *
 * @author Kevin
 * @date 2026-09-17
 */
class WrenAdminSessionServiceTest {

    /** 验证正确 Token 能签发互不相同的 Session。 */
    @Test
    void shouldCreateIndependentSessionsForValidToken() {
        WrenProperties properties = properties();
        WrenAdminSessionService service = new WrenAdminSessionService(
                properties, Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC), new SecureRandom());

        WrenAdminSessionService.Session first = service.create("admin-secret");
        WrenAdminSessionService.Session second = service.create("admin-secret");

        assertNotEquals(first.id(), second.id());
        assertTrue(service.touch(first.id()).isPresent());
    }

    /** 验证错误 Token 必须被拒绝。 */
    @Test
    void shouldRejectInvalidToken() {
        WrenAdminSessionService service = new WrenAdminSessionService(properties());

        assertThrows(WrenAdminSessionService.InvalidAdminTokenException.class,
                () -> service.create("wrong-secret"));
    }

    private WrenProperties properties() {
        WrenProperties properties = new WrenProperties();
        properties.setAdminToken("admin-secret");
        properties.setAdminSessionTimeout(Duration.ofMinutes(30));
        return properties;
    }
}
