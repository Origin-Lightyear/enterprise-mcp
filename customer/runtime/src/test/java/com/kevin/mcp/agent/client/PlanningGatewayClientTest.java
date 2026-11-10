package com.kevin.mcp.agent.client;

import com.kevin.mcp.agent.configuration.PlanningGatewayProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Catalog HTTP 错误在客户端边界保留状态并限制可展示内容。
 *
 * @author Kevin
 * @date 2026-10-23
 */
class PlanningGatewayClientTest {

    /** 验证云端业务拒绝保留 HTTP 状态及结构化错误原因。 */
    @Test
    void shouldExposeStructuredCatalogRejection() throws Exception {
        assertRejected(400,
                "{\"code\":\"INVALID_REQUEST\",\"message\":\"Planning Catalog capabilities are empty\"}",
                "HTTP 400 [INVALID_REQUEST]: Planning Catalog capabilities are empty", "unused-secret");
    }

    /** 验证代理页面和服务器内部异常不进入可展示错误。 */
    @Test
    void shouldHideUnstructuredAndServerErrors() throws Exception {
        assertRejected(502, "<html>database-password=secret</html>", "HTTP 502", "database-password");
        assertRejected(500, "{\"code\":\"INVALID_REQUEST\",\"message\":\"database-password=secret\"}",
                "HTTP 500", "database-password");
        assertRejected(400, "<html>proxy-secret</html>", "HTTP 400", "proxy-secret");
    }

    private void assertRejected(int status, String body, String expected, String hidden) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/control-plane/catalog-snapshots/confirm", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            PlanningGatewayProperties properties = new PlanningGatewayProperties();
            properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/control-plane/planning/plans");
            PlanningGatewayClient client = new PlanningGatewayClient(properties);

            assertThatThrownBy(() -> client.confirmCatalog(Map.of("catalogJson", "{}")))
                    .isInstanceOf(PlanningGatewayClient.CatalogRequestException.class)
                    .hasMessageContaining(expected).hasMessageNotContaining(hidden)
                    .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                            ((PlanningGatewayClient.CatalogRequestException) exception).statusCode()).isEqualTo(status));
        } finally {
            server.stop(0);
        }
    }
}
