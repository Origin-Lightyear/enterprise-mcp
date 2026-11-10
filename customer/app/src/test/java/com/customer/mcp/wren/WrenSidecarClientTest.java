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

    /** 验证候选与提交接口均接受删除最后一个源后的空快照。 */
    @Test
    void shouldAcceptEmptyDeletionSnapshotOverHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String token = "a".repeat(32);
        server.createContext("/internal/admin/mdl-candidates", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean preparation = exchange.getRequestURI().getPath().endsWith("mdl-candidates");
            if (preparation) {
                assertTrue(request.contains("\"deleteDatasource\":true"));
            }
            byte[] response = ("{\"data\":{\"candidateToken\":\"" + token
                    + "\",\"configured\":false,\"mdlDigest\":\"\",\"manifest\":{}}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            WrenProperties properties = new WrenProperties();
            properties.setEnabled(true);
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            WrenSidecarClient client = new WrenSidecarClient(properties);
            assertEquals("", client.prepareDeletion("sales").snapshot().digest());
            assertTrue(client.commit(token, "old").manifest().isEmpty());
        } finally {
            server.stop(0);
        }
    }

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

    /** 验证数据源目录分别保留稳定标识和中文显示名称。 */
    @Test
    void shouldReadDatasourceDisplayNamesFromAuthenticatedAdapterCatalog() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] response = ("{\"data\":{\"items\":[{\"name\":\"sales\","
                + "\"displayName\":\"销售数据库\"}]}}").getBytes(StandardCharsets.UTF_8);
        server.createContext("/internal/admin/datasources", exchange -> {
            assertEquals("service-token", exchange.getRequestHeaders().getFirst("X-Wren-Service-Token"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            WrenProperties properties = new WrenProperties();
            properties.setEnabled(true);
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setAdapterServiceToken("service-token");

            assertEquals(Map.of("sales", "销售数据库"),
                    new WrenSidecarClient(properties).datasourceDisplayNames());
        } finally {
            server.stop(0);
        }
    }
}
