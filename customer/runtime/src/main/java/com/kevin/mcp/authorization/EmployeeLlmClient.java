package com.kevin.mcp.authorization;

import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.agent.model.EmployeeLlmInvocationConfig;
import com.kevin.mcp.util.GsonUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 使用员工请求级配置调用 OpenAI 兼容模型。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@Component
public class EmployeeLlmClient {

    private static final Type RESPONSE_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final Duration connectTimeout;
    private final Duration readTimeout;

    /**
     * 创建员工 LLM 客户端。
     *
     * @param connectTimeout 连接超时
     * @param readTimeout 响应超时
     */
    public EmployeeLlmClient(
            @Value("${enterprise.permission-review.connect-timeout:3s}") Duration connectTimeout,
            @Value("${enterprise.permission-review.read-timeout:60s}") Duration readTimeout
    ) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /**
     * 调用员工模型并返回非流式文本结果。
     *
     * @param config 员工请求级配置
     * @param systemPrompt 权限审查系统规则
     * @param userPrompt 当前请求上下文
     * @return 模型返回文本
     * @throws IOException 网络、状态码或响应格式异常
     */
    public String chat(EmployeeLlmInvocationConfig config, String systemPrompt, String userPrompt) throws IOException {
        validate(config);
        HttpURLConnection connection = (HttpURLConnection) URI.create(chatUrl(config.baseUrl())).toURL().openConnection();
        connection.setConnectTimeout(Math.toIntExact(connectTimeout.toMillis()));
        connection.setReadTimeout(Math.toIntExact(readTimeout.toMillis()));
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + config.apiKey());
        Map<String, Object> payload = Map.of(
                "model", config.model(),
                "stream", false,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        try {
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(GsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8));
            }
            int statusCode = connection.getResponseCode();
            if (statusCode >= 400) {
                readBody(connection.getErrorStream());
                throw new IOException("Employee LLM request failed, status: " + statusCode);
            }
            Map<String, Object> response = GsonUtil.fromJson(readBody(connection.getInputStream()), RESPONSE_TYPE);
            return extractContent(response);
        } finally {
            connection.disconnect();
        }
    }

    private String extractContent(Map<String, Object> response) throws IOException {
        Object choicesValue = response == null ? null : response.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()
                || !(choices.getFirst() instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String content)
                || content.isBlank()) {
            throw new IOException("Employee LLM returned an empty response");
        }
        return content;
    }

    private String readBody(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        try (InputStream stream = inputStream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String chatUrl(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized.endsWith("/v1") ? normalized + "/chat/completions" : normalized + "/v1/chat/completions";
    }

    private void validate(EmployeeLlmInvocationConfig config) {
        if (config == null || isBlank(config.baseUrl()) || isBlank(config.apiKey()) || isBlank(config.model())) {
            throw new IllegalStateException("Employee LLM invocation configuration is incomplete");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
