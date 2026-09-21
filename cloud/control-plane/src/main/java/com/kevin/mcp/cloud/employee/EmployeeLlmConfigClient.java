package com.kevin.mcp.cloud.employee;

import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.cloud.configuration.ControlPlanePlanningProperties;
import com.kevin.mcp.llm.LlmModelSelection;
import com.kevin.mcp.util.GsonUtil;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 调用 Tenant 内部接口读取员工 LLM 配置，并从员工 NewAPI 查询可用模型。
 *
 * <p>异常只保留 HTTP 状态和固定说明，不包含 Tenant 响应体、API Key 或 Authorization。</p>
 *
 * @author Kevin
 * @date 2026-09-10
 */
@Component
public class EmployeeLlmConfigClient {

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final ControlPlanePlanningProperties properties;

    /**
     * 创建员工 LLM 配置客户端。
     *
     * @param properties Control Plane 规划配置
     */
    public EmployeeLlmConfigClient(ControlPlanePlanningProperties properties) {
        this.properties = properties;
    }

    /**
     * 按租户和员工读取专属 LLM 配置。
     *
     * @param tenantId 租户标识
     * @param employeeId 员工标识
     * @return Tenant 返回的员工配置
     * @throws IOException 网络、状态码或响应格式异常
     */
    public EmployeeLlmConfig fetchConfig(String tenantId, String employeeId) throws IOException {
        String tenantBaseUrl = requireText(properties.getTenantServiceBaseUrl(),
                "Tenant service base URL is not configured");
        String path = requireText(properties.getEmployeeLlmConfigPath(),
                "Employee LLM config path is not configured");
        HttpURLConnection connection = openConnection(joinUrl(tenantBaseUrl, path), "POST",
                properties.getTenantConnectTimeout().toMillis(), properties.getTenantReadTimeout().toMillis());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "application/json");
        try {
            Map<String, Object> request = Map.of("tenantId", tenantId, "employeeId", employeeId);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(GsonUtil.toJson(request).getBytes(StandardCharsets.UTF_8));
            }
            int statusCode = connection.getResponseCode();
            String responseBody = readBody(connection, statusCode >= 400);
            if (statusCode >= 400) {
                throw new IOException("Tenant employee LLM config request failed, status: " + statusCode);
            }
            return parseTenantResponse(responseBody);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 使用员工密钥查询 NewAPI 完整模型目录并按 endpoint 能力分配用途。
     *
     * @param config Tenant 返回的员工配置
     * @return 生成、向量和重排模型选择结果
     * @throws IOException 网络、状态码或响应格式异常
     */
    public LlmModelSelection resolveModels(EmployeeLlmConfig config) throws IOException {
        validateEmployeeConfig(config, false);
        HttpURLConnection connection = openConnection(joinUrl(config.newapiUrl(), "/v1/models"), "GET",
                properties.getLlmConnectTimeout().toMillis(), properties.getLlmReadTimeout().toMillis());
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + config.apiKey());
        try {
            int statusCode = connection.getResponseCode();
            String responseBody = readBody(connection, statusCode >= 400);
            if (statusCode >= 400) {
                throw new IOException("Employee LLM model request failed, status: " + statusCode);
            }
            return parseModels(responseBody, config.model());
        } finally {
            connection.disconnect();
        }
    }

    private EmployeeLlmConfig parseTenantResponse(String responseBody) throws IOException {
        Map<String, Object> response = parseObject(responseBody, "Tenant employee LLM config response is invalid");
        Object codeValue = response.get("code");
        if (!(codeValue instanceof Number code) || code.intValue() != 0) {
            throw new IOException("Tenant employee LLM config request was rejected");
        }
        if (!(response.get("data") instanceof Map<?, ?> data)) {
            throw new IOException("Tenant employee LLM config response data is missing");
        }
        EmployeeLlmConfig config = new EmployeeLlmConfig(
                textValue(data.get("apiKey")), textValue(data.get("newapiUrl")), textValue(data.get("model")));
        validateEmployeeConfig(config, false);
        return config;
    }

    private LlmModelSelection parseModels(String responseBody, String preferredGenerationModel) throws IOException {
        Map<String, Object> response = parseObject(responseBody, "Employee LLM model response is invalid");
        try {
            return LlmModelSelection.fromResponse(response, preferredGenerationModel);
        } catch (IllegalStateException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    private Map<String, Object> parseObject(String responseBody, String message) throws IOException {
        try {
            Map<String, Object> response = GsonUtil.fromJson(responseBody, MAP_TYPE);
            if (response == null) {
                throw new IOException(message);
            }
            return response;
        } catch (RuntimeException exception) {
            throw new IOException(message, exception);
        }
    }

    private HttpURLConnection openConnection(String url, String method,
                                             long connectTimeoutMillis, long readTimeoutMillis) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(Math.toIntExact(connectTimeoutMillis));
        connection.setReadTimeout(Math.toIntExact(readTimeoutMillis));
        connection.setRequestMethod(method);
        return connection;
    }

    private String readBody(HttpURLConnection connection, boolean error) throws IOException {
        InputStream inputStream = error ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream == null) {
            return "";
        }
        try (InputStream stream = inputStream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void validateEmployeeConfig(EmployeeLlmConfig config, boolean requireModel) {
        if (config == null || isBlank(config.newapiUrl()) || isBlank(config.apiKey())
                || (requireModel && isBlank(config.model()))) {
            throw new IllegalStateException("Employee LLM configuration is incomplete");
        }
    }

    private String joinUrl(String baseUrl, String path) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBaseUrl + normalizedPath;
    }

    private String requireText(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private String textValue(Object value) {
        return value instanceof String text ? text : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
