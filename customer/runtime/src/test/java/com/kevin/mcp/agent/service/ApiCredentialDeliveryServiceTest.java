package com.kevin.mcp.agent.service;

import com.kevin.mcp.agent.model.ApiCredential;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证员工 API 凭证仅能完成本机已登记的一次性投递请求。
 *
 * @author Kevin
 * @date 2026-10-26
 */
class ApiCredentialDeliveryServiceTest {

    /** 验证先登记后投递可返回对应员工凭证。 */
    @Test
    void shouldReceiveCredentialForPendingRequest() {
        ApiCredentialDeliveryService service = new ApiCredentialDeliveryService(Duration.ofSeconds(1));
        ApiCredential credential = new ApiCredential("UNIQUE_IDENTIFIER", Map.of("identifier", "employee-1"),
                LocalDateTime.of(2026, 10, 26, 12, 0));

        ApiCredential delivered = service.request("request-1", () -> service.deliver("request-1", credential));

        assertThat(delivered).isEqualTo(credential);
    }

    /** 验证未知或已结束的编号无法向 Runtime 注入凭证。 */
    @Test
    void shouldRejectUnknownOrRepeatedDelivery() {
        ApiCredentialDeliveryService service = new ApiCredentialDeliveryService(Duration.ofSeconds(1));
        ApiCredential credential = new ApiCredential("UNIQUE_IDENTIFIER", Map.of("identifier", "employee-1"), null);

        assertThatThrownBy(() -> service.deliver("unknown", credential))
                .isInstanceOf(SecurityException.class);
        service.request("request-1", () -> service.deliver("request-1", credential));
        assertThatThrownBy(() -> service.deliver("request-1", credential))
                .isInstanceOf(SecurityException.class);
    }
}
