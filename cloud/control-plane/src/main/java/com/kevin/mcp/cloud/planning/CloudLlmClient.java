package com.kevin.mcp.cloud.planning;

import com.kevin.mcp.cloud.configuration.ControlPlanePlanningProperties;
import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.cloud.employee.EmployeeLlmInvocationConfig;
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
 * 使用请求级员工配置调用云端规划 LLM。
 *
 * <p>该实现归云端所有，但连接地址、密钥和模型来自当前员工配置，禁止在 Bean 中保存共享凭据。</p>
 *
 * @author Kevin
 * @date 2026-09-10
 */
@Component
public class CloudLlmClient {

    private final ControlPlanePlanningProperties properties;

    /**
     * 创建云端 LLM 客户端。
     *
     * @param properties Control Plane 规划配置
     */
    public CloudLlmClient(ControlPlanePlanningProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据 system 规则和用户上下文生成非流式规划结果。
     *
     * @param invocationConfig 当前员工的请求级 LLM 调用配置
     * @param systemPrompt 固定规划规则和输出协议
     * @param userPrompt 用户意图和客户能力 Catalog
     * @return 模型返回的文本内容
     * @throws IOException 网络、状态码或响应格式异常
     */
    public String chat(EmployeeLlmInvocationConfig invocationConfig,
                       String systemPrompt, String userPrompt) throws IOException {
        validateConfiguration(invocationConfig);
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                normalizeBaseUrl(invocationConfig.baseUrl()) + "/v1/chat/completions").toURL().openConnection();
        connection.setConnectTimeout(Math.toIntExact(properties.getLlmConnectTimeout().toMillis()));
        connection.setReadTimeout(Math.toIntExact(properties.getLlmReadTimeout().toMillis()));
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + invocationConfig.apiKey());
        Map<String, Object> payload = Map.of(
                "model", invocationConfig.model(),
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt == null ? "" : systemPrompt),
                        Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)
                )
        );
        try {
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(GsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8));
            }
            int statusCode = connection.getResponseCode();
            String responseBody = readBody(connection, statusCode >= 400);
            if (statusCode >= 400) {
                throw new IOException("Control Plane LLM request failed, status: " + statusCode);
            }
            Type responseType = new TypeToken<Map<String, Object>>() { }.getType();
            return extractContent(GsonUtil.fromJson(responseBody, responseType));
        } finally {
            connection.disconnect();
        }
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

    private String extractContent(Map<String, Object> response) throws IOException {
        Object choicesValue = response == null ? null : response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()
                || !(choices.getFirst() instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String content)
                || content.isBlank()) {
            throw new IOException("Control Plane LLM returned an empty response");
        }
        return content;
    }

    private void validateConfiguration(EmployeeLlmInvocationConfig invocationConfig) {
        if (invocationConfig == null || isBlank(invocationConfig.baseUrl()) || isBlank(invocationConfig.apiKey())
                || isBlank(invocationConfig.model())) {
            throw new IllegalStateException("Employee LLM invocation configuration is incomplete");
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
