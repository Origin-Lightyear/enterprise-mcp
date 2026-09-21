package com.customer.mcp.wren;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 customerapp 能完整识别 Wren Adapter 的结构化查询错误。
 *
 * @author Kevin
 * @date 2026-09-20
 */
class WrenSidecarClientTest {

    @Test
    void shouldExposeAdapterErrorBodyToCustomerAppAudit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] response = ("{\"ok\":false,\"code\":\"INVALID_REQUEST\","
                + "\"message\":\"INVALID_REQUEST\",\"data\":{\"error\":\"model not found\","
                + "\"errorType\":\"ValueError\",\"path\":\"/internal/query\"},"
                + "\"requestId\":\"request-123\"}").getBytes(StandardCharsets.UTF_8);
        server.createContext("/internal/query", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            WrenProperties properties = new WrenProperties();
            properties.setEnabled(true);
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            WrenSidecarClient client = new WrenSidecarClient(properties);

            WrenSidecarClient.WrenQueryClientException exception = assertThrows(
                    WrenSidecarClient.WrenQueryClientException.class,
                    () -> client.query(Map.of("question", "query stores"))
            );

            assertEquals(400, exception.getStatus());
            assertTrue(exception.getResponseBody().contains("request-123"));
            assertTrue(exception.getMessage().contains("model not found"));
        } finally {
            server.stop(0);
        }
    }
}
