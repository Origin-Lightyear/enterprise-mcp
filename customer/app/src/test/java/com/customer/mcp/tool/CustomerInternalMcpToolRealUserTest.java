package com.customer.mcp.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 验证真实员工通过已部署 Runtime 发起可重复配置的 MCP Tool 请求。
 *
 * <p>测试直接调用已启动 Runtime 的 MCP Streamable HTTP 入口，因此制品摘要、Catalog 和 MDL
 * 均由 Runtime 按生产流程自动维护。手机号、租户、问题、Tool 和服务地址均可通过
 * {@code REAL_MCP_*} 环境变量覆盖，登录密码只通过环境变量注入，禁止写入仓库或测试日志。</p>
 *
 * @author Kevin
 * @date 2026-09-20
 */
@EnabledIfEnvironmentVariable(named = "REAL_MCP_LIVE_TEST", matches = "true")
class CustomerInternalMcpToolRealUserTest {

    private static final Logger log = LoggerFactory.getLogger(CustomerInternalMcpToolRealUserTest.class);

    /** 测试流程节点总数。 */
    private static final int STEP_COUNT = 8;

    /** 默认真实员工登录手机号。 */
    private static final String DEFAULT_MOBILE = "13666666666";

    /** 默认真实员工所属租户编号。 */
    private static final String DEFAULT_TENANT_ID = "1";

    /** 默认真实业务问题。 */
    private static final String DEFAULT_QUESTION = "查询公司ID是1的所有门店用户信息";

    /** 默认 MCP Tool 名称。 */
    private static final String DEFAULT_TOOL_NAME = "linko_ent";

    /** MCP initialize 请求编号。 */
    private static final int INITIALIZE_REQUEST_ID = 1;

    /** MCP Tool 调用请求编号。 */
    private static final int TOOL_CALL_REQUEST_ID = 2;

    /** JSON 编解码器。 */
    private final Gson gson = new Gson();

    /** 过程日志使用的格式化 JSON 编解码器。 */
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 验证指定真实员工能够完成 MCP 请求并取得成功执行回执。
     *
     * @throws Exception 登录、MCP 协议或真实业务查询失败
     */
    @Test
    void shouldExecuteMcpRequestAsRealEmployee() throws Exception {
        long testStartedAt = System.nanoTime();
        RealMcpScenario scenario = scenario();
        logStep(1, "加载测试场景", "mobile={}, tenantId={}, tool={}, tenantUrl={}, runtimeUrl={}, question={}",
                maskMobile(scenario.mobile()), scenario.tenantId(), scenario.toolName(), scenario.tenantUrl(),
                scenario.runtimeUrl(), scenario.question());

        try (HttpClient client = HttpClient.newHttpClient()) {
            long startedAt = System.nanoTime();
            logStep(2, "真实员工登录", "开始请求 Tenant 登录接口");
            JsonObject loginData = apiData(postJson(client, scenario.tenantUrl() + "/api/v1/agent/inner/auth",
                    Map.of("mobile", scenario.mobile(), "password", scenario.password()), Map.of()), "真实员工登录");
            logStep(2, "真实员工登录", "成功，tenantCount={}, elapsedMs={}",
                    loginData.getAsJsonArray("tenants").size(), elapsedMillis(startedAt));

            startedAt = System.nanoTime();
            logStep(3, "选择目标租户", "开始匹配 tenantId={}", scenario.tenantId());
            JsonObject tenant = requireTenant(loginData, scenario.tenantId());
            logStep(3, "选择目标租户", "成功，tenantId={}, elapsedMs={}", scenario.tenantId(),
                    elapsedMillis(startedAt));

            startedAt = System.nanoTime();
            logStep(4, "领取 MCP 凭证", "开始请求 instanceId={}", scenario.instanceId());
            JsonObject credentialData = apiData(postJson(client,
                    scenario.tenantUrl() + "/api/v1/agent/inner/mcp-credentials",
                    Map.of("mcpInstanceId", scenario.instanceId()),
                    Map.of("Authorization", "AgentSession " + requiredText(tenant, "agentSessionToken"))),
                    "领取 MCP 凭证");
            Map<String, String> credentialHeaders = credentialHeaders(credentialData);
            logStep(4, "领取 MCP 凭证", "成功，headerNames={}, elapsedMs={}", credentialHeaders.keySet(),
                    elapsedMillis(startedAt));

            startedAt = System.nanoTime();
            logStep(5, "初始化 MCP Session", "开始发送 initialize");
            String sessionId = initializeMcpSession(client, scenario.runtimeUrl());
            logStep(5, "初始化 MCP Session", "成功，sessionId={}, elapsedMs={}", abbreviate(sessionId),
                    elapsedMillis(startedAt));

            startedAt = System.nanoTime();
            logStep(6, "确认 MCP 初始化", "开始发送 notifications/initialized");
            notifyMcpInitialized(client, scenario.runtimeUrl(), sessionId);
            logStep(6, "确认 MCP 初始化", "成功，elapsedMs={}", elapsedMillis(startedAt));

            startedAt = System.nanoTime();
            logStep(7, "调用 MCP Tool", "开始调用 tool={}, question={}", scenario.toolName(), scenario.question());
            JsonObject toolResult = callTool(client, scenario.runtimeUrl(), sessionId, credentialHeaders,
                    scenario.toolName(), scenario.question());
            logStep(7, "调用 MCP Tool", "收到 MCP 响应，elapsedMs={}, contentCount={}, hasStructuredContent={}",
                    elapsedMillis(startedAt), toolResult.getAsJsonArray("content").size(),
                    toolResult.has("structuredContent"));

            startedAt = System.nanoTime();
            JsonObject payload = resultPayload(toolResult, scenario.toolName());
            validatePayload(payload, scenario);
            logStep(8, "解析并校验结果", "成功，elapsedMs={}, totalElapsedMs={}，业务结果如下：\n{}",
                    elapsedMillis(startedAt), elapsedMillis(testStartedAt), prettyGson.toJson(payload));
        }
    }

    private JsonObject postJson(HttpClient client, String url, Object body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8));
        headers.forEach(request::header);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), () -> "HTTP 请求失败: " + url);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject apiData(JsonObject response, String operation) {
        assertEquals("0", response.get("code").getAsString(), () -> operation + "失败: " + response);
        assertTrue(response.has("data") && response.get("data").isJsonObject(), "Tenant 响应必须包含 data");
        return response.getAsJsonObject("data");
    }

    private JsonObject requireTenant(JsonObject loginData, String tenantId) {
        assertTrue(loginData.has("tenants") && loginData.get("tenants").isJsonArray(),
                "真实员工登录响应必须包含租户列表");
        for (JsonElement item : loginData.getAsJsonArray("tenants")) {
            JsonObject tenant = item.getAsJsonObject();
            if (tenantId.equals(tenant.get("tenantId").getAsString())) {
                return tenant;
            }
        }
        throw new AssertionError("真实员工不属于目标租户: " + tenantId);
    }

    private String initializeMcpSession(HttpClient client, String runtimeUrl)
            throws IOException, InterruptedException {
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0", "id", INITIALIZE_REQUEST_ID, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "customer-internal-tool-real-user-test", "version", "1.0.0")));
        HttpResponse<String> response = sendMcp(client, runtimeUrl, request, Map.of());
        return response.headers().firstValue("Mcp-Session-Id")
                .orElseThrow(() -> new AssertionError("MCP initialize 响应缺少 Session ID"));
    }

    private void notifyMcpInitialized(HttpClient client, String runtimeUrl, String sessionId)
            throws IOException, InterruptedException {
        sendMcp(client, runtimeUrl, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"),
                Map.of("Mcp-Session-Id", sessionId));
    }

    private JsonObject callTool(HttpClient client, String runtimeUrl, String sessionId,
                                Map<String, String> credentialHeaders, String toolName, String question)
            throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>(credentialHeaders);
        headers.put("Mcp-Session-Id", sessionId);
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0", "id", TOOL_CALL_REQUEST_ID, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", Map.of("message", question)));
        JsonObject rpcResponse = findRpcResponse(sendMcp(client, runtimeUrl, request, headers).body(),
                TOOL_CALL_REQUEST_ID);
        assertFalse(rpcResponse.has("error"), () -> "MCP Tool 调用失败: " + rpcResponse);
        JsonObject result = rpcResponse.getAsJsonObject("result");
        assertNotNull(result, "MCP Tool 响应必须包含 result");
        assertFalse(result.has("isError") && result.get("isError").getAsBoolean(),
                () -> "MCP Tool 返回业务错误: " + result);
        return result;
    }

    private HttpResponse<String> sendMcp(HttpClient client, String runtimeUrl, Object body,
                                         Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(runtimeUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8));
        headers.forEach(request::header);
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 202,
                () -> "MCP HTTP 请求失败，状态码: " + response.statusCode());
        return response;
    }

    private JsonObject findRpcResponse(String responseBody, int requestId) {
        for (String line : responseBody.lines().toList()) {
            if (line.startsWith("data:")) {
                JsonObject message = JsonParser.parseString(line.substring("data:".length())).getAsJsonObject();
                if (message.has("method")) {
                    logStep(7, "Runtime 过程通知", "method={}, params={}", message.get("method"),
                            message.has("params") ? prettyGson.toJson(message.get("params")) : "{}");
                }
                if (message.has("id") && message.get("id").getAsInt() == requestId) {
                    return message;
                }
            }
        }
        JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
        assertEquals(requestId, response.get("id").getAsInt());
        return response;
    }

    private Map<String, String> credentialHeaders(JsonObject credentialData) {
        assertTrue(credentialData.has("headers") && credentialData.get("headers").isJsonObject(),
                "MCP 凭证响应必须包含 headers");
        Map<String, String> headers = new LinkedHashMap<>();
        credentialData.getAsJsonObject("headers").entrySet()
                .forEach(entry -> headers.put(entry.getKey(), entry.getValue().getAsString()));
        return headers;
    }

    private JsonObject resultPayload(JsonObject toolResult, String toolName) {
        JsonElement payload = toolResult.get("structuredContent");
        if (payload == null || payload.isJsonNull()) {
            payload = JsonParser.parseString(toolResult.getAsJsonArray("content")
                    .get(0).getAsJsonObject().get("text").getAsString());
        }
        if (payload.isJsonObject()) {
            JsonObject toolPayload = payload.getAsJsonObject();
            if (toolPayload.has("false")) {
                log.error("[真实 MCP 测试][8/{}][解析并校验结果] Tool 返回失败，原始 result 如下：\n{}",
                        STEP_COUNT, prettyGson.toJson(toolResult));
                fail(toolName + " 执行失败: " + toolPayload.get("false").getAsString());
            }
            if (toolPayload.has("true")) {
                payload = JsonParser.parseString(toolPayload.get("true").getAsString());
            }
        }
        assertTrue(payload.isJsonObject(), "MCP Tool 成功结果必须是 JSON 对象");
        JsonObject result = payload.getAsJsonObject();
        assertTrue(result.has("executionReceipt") && result.get("executionReceipt").isJsonObject(),
                "成功结果必须包含执行回执");
        assertTrue(result.has("data") && result.get("data").isJsonObject(), "成功结果必须包含业务数据");
        return result;
    }

    private void validatePayload(JsonObject payload, RealMcpScenario scenario) {
        JsonObject receipt = payload.getAsJsonObject("executionReceipt");
        assertEquals("SUCCEEDED", receipt.get("status").getAsString(), "执行回执状态必须成功");
        JsonObject queryData = payload.getAsJsonObject("data");
        if (queryData.has("data") && queryData.get("data").isJsonObject()) {
            queryData = queryData.getAsJsonObject("data");
        }
        if (scenario.expectedMinRows() >= 0) {
            assertTrue(queryData.has("rowCount"), "业务结果缺少 rowCount，可将 REAL_MCP_EXPECT_MIN_ROWS 设为 -1 跳过");
            assertTrue(queryData.get("rowCount").getAsInt() >= scenario.expectedMinRows(),
                    () -> "业务结果行数少于预期: " + scenario.expectedMinRows());
        }
        if (scenario.expectedTruncated() != null) {
            assertTrue(queryData.has("truncated"), "业务结果缺少 truncated，可将 REAL_MCP_EXPECT_TRUNCATED 设为 ignore 跳过");
            assertEquals(scenario.expectedTruncated(), queryData.get("truncated").getAsBoolean(),
                    "业务结果截断状态不符合预期");
        }
    }

    private String requiredText(JsonObject source, String field) {
        assertTrue(source.has(field) && !source.get(field).getAsString().isBlank(), () -> field + " 不能为空");
        return source.get(field).getAsString();
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertNotNull(value, () -> "缺少环境变量 " + name);
        assertFalse(value.isBlank(), () -> "环境变量 " + name + " 不能为空");
        return value;
    }

    private String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private RealMcpScenario scenario() {
        return new RealMcpScenario(
                environmentOrDefault("REAL_MCP_MOBILE", DEFAULT_MOBILE),
                requiredEnvironment("REAL_MCP_PASSWORD"),
                environmentOrDefault("REAL_MCP_TENANT_ID", DEFAULT_TENANT_ID),
                environmentOrDefault("REAL_MCP_QUESTION", DEFAULT_QUESTION),
                environmentOrDefault("REAL_MCP_TOOL_NAME", DEFAULT_TOOL_NAME),
                environmentOrDefault("REAL_MCP_TENANT_URL", "http://127.0.0.1:8002"),
                environmentOrDefault("REAL_MCP_RUNTIME_URL", "http://127.0.0.1:6001/mcp"),
                environmentOrDefault("REAL_MCP_INSTANCE_ID", "mcp-1-b7074066-680e-45f3-b421-835542d349db"),
                Integer.parseInt(environmentOrDefault("REAL_MCP_EXPECT_MIN_ROWS", "1")),
                expectedTruncated()
        );
    }

    private Boolean expectedTruncated() {
        String value = environmentOrDefault("REAL_MCP_EXPECT_TRUNCATED", "false");
        if ("ignore".equalsIgnoreCase(value)) {
            return null;
        }
        assertTrue("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value),
                "REAL_MCP_EXPECT_TRUNCATED 只能是 true、false 或 ignore");
        return Boolean.parseBoolean(value);
    }

    private void logStep(int step, String name, String message, Object... arguments) {
        log.info("[真实 MCP 测试][{}/{}][{}] " + message, prepend(step, name, arguments));
    }

    private Object[] prepend(int step, String name, Object[] arguments) {
        Object[] values = new Object[arguments.length + 3];
        values[0] = step;
        values[1] = STEP_COUNT;
        values[2] = name;
        System.arraycopy(arguments, 0, values, 3, arguments.length);
        return values;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String maskMobile(String mobile) {
        if (mobile.length() < 7) {
            return "***";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    private record RealMcpScenario(
            String mobile,
            String password,
            String tenantId,
            String question,
            String toolName,
            String tenantUrl,
            String runtimeUrl,
            String instanceId,
            int expectedMinRows,
            Boolean expectedTruncated
    ) {
    }
}
