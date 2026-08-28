package com.kevin.mcp.processor;

import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.agent.connector.entity.EmployeeLlmConfig;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处理大模型请求，并支持按单次 MCP 请求使用员工专属配置隔离访问凭证。
 *
 * @author Kevin
 * @date 2026-07-20
 */
@Component
public class LLMProcessor {

    private static final Logger log = LoggerFactory.getLogger(LLMProcessor.class);

    /**
     * 查询指定员工可访问的模型列表，兼容 OpenAI 风格的 GET /v1/models。
     *
     * @param targetBaseUrl 员工 NewAPI 基础地址
     * @param targetApiKey 员工专属 API Key
     * @return 可用模型 ID 列表；连接失败时返回 null
     */
    private List<String> fetchModelList(String targetBaseUrl, String targetApiKey) {
        HttpURLConnection connection = null;
        try {
            connection = this.openModelsConnection(targetBaseUrl, targetApiKey);
            int statusCode = connection.getResponseCode();
            String responseBody = this.readResponseBody(connection, statusCode >= 400);
            if (statusCode >= 400) {
                log.warn("LLM 模型列表查询失败，状态码: {}，响应: {}", statusCode, responseBody);
                return null;
            }
            Type responseType = new TypeToken<Map<String, Object>>() {
            }.getType();
            Map<String, Object> response = GsonUtil.fromJson(responseBody, responseType);
            List<String> modelIds = new ArrayList<>();
            Object dataObject = response.get("data");
            if (dataObject instanceof List<?> dataList) {
                for (Object item : dataList) {
                    if (item instanceof Map<?, ?> itemMap) {
                        Object idObject = itemMap.get("id");
                        if (idObject instanceof String id) {
                            modelIds.add(id);
                        }
                    }
                }
            }
            return modelIds;
        } catch (Exception exception) {
            log.warn("LLM 模型列表查询异常: {}", exception.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 打开模型列表接口连接。配置加载使用较短超时，避免缓存刷新长时间阻塞。
     *
     * @param targetBaseUrl 员工 NewAPI 基础地址
     * @param targetApiKey 员工专属 API Key
     * @return HTTP 连接对象
     * @throws IOException 打开连接失败
     */
    private HttpURLConnection openModelsConnection(String targetBaseUrl, String targetApiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(targetBaseUrl + "/v1/models").toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + targetApiKey);
        return connection;
    }

    /**
     * 打开聊天补全接口连接并设置通用请求头。
     *
     * @param invocationConfig 本次请求的员工 LLM 调用配置
     * @param accept 响应内容类型
     * @return HTTP 连接对象
     * @throws IOException 打开连接失败
     */
    private HttpURLConnection openChatConnection(LlmInvocationConfig invocationConfig, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(invocationConfig.baseUrl() + "/v1/chat/completions").toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(300_000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Authorization", "Bearer " + invocationConfig.apiKey());
        return connection;
    }

    /**
     * 校验员工 LLM 配置并解析默认模型，生成可在单次 MCP 请求内安全复用的不可变配置。
     *
     * @param employeeConfig Platform 返回的员工 LLM 配置
     * @return 已归一化地址并解析默认模型的调用配置
     */
    public LlmInvocationConfig prepareInvocationConfig(EmployeeLlmConfig employeeConfig) {
        if (employeeConfig == null) {
            throw new IllegalStateException("Platform employee LLM config is null");
        }
        String employeeBaseUrl = this.normalizeBaseUrl(this.requireText(employeeConfig.newapiUrl(), "newapiUrl"));
        String employeeApiKey = this.normalizeText(this.requireText(employeeConfig.apiKey(), "apiKey"));
        String employeeModel = this.resolveFirstAvailableModel(employeeBaseUrl, employeeApiKey);
        return new LlmInvocationConfig(employeeBaseUrl, employeeApiKey, employeeModel);
    }

    /**
     * 使用员工专属配置执行一次普通非流式对话，调用期间不读取全局租户密钥。
     *
     * @param invocationConfig 本次 MCP 请求的员工 LLM 调用配置
     * @param userPrompt 用户输入内容
     * @return 模型返回文本
     * @throws IOException 请求失败
     */
    public String chat(LlmInvocationConfig invocationConfig, String userPrompt) throws IOException {
        this.requireInvocationConfig(invocationConfig);
        return this.chat(invocationConfig, invocationConfig.model(), "", userPrompt, BigDecimal.valueOf(0.7D), false);
    }

    private String chat(LlmInvocationConfig invocationConfig, String modelName, String systemPrompt, String userPrompt,
                        BigDecimal temperature, boolean enableThinking) throws IOException {
        Map<String, Object> payload = this.buildChatPayloadWithSystemPrompt(modelName, systemPrompt, userPrompt, false,
                temperature, enableThinking);
        Map<String, Object> response = this.executeChatCompletion(invocationConfig, payload);
        return this.extractAssistantContent(response);
    }

    /**
     * 构造带 system 消息的聊天请求体，避免把系统提示词直接拼接进用户消息。
     *
     * @param modelName 模型名称
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户输入
     * @param stream 是否流式返回
     * @param temperature 采样温度
     * @param enableThinking 是否启用推理模式
     * @return 请求体 Map
     */
    private Map<String, Object> buildChatPayloadWithSystemPrompt(String modelName, String systemPrompt, String userPrompt,
                                                                 boolean stream, BigDecimal temperature,
                                                                 boolean enableThinking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", this.requireText(modelName, "model"));
        payload.put("stream", stream);
        payload.put("temperature", temperature == null ? BigDecimal.valueOf(0.7D) : temperature);
        if (!enableThinking) {
            payload.put("thinking", Map.of("type", "disabled"));
        }


        List<Map<String, String>> messages;
        if (systemPrompt == null || systemPrompt.isBlank()) {
            messages = List.of(
                    Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)
            );
        } else {
            messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt)
            );
        }
        payload.put("messages", messages);
        return payload;
    }

    /**
     * 发送聊天补全请求，并统一解析响应 JSON。
     *
     * @param invocationConfig 本次请求的员工 LLM 调用配置
     * @param payload 请求体
     * @return 响应结构
     * @throws IOException 请求失败
     */
    private Map<String, Object> executeChatCompletion(LlmInvocationConfig invocationConfig,
                                                      Map<String, Object> payload) throws IOException {
        this.requireInvocationConfig(invocationConfig);
        HttpURLConnection connection = this.openChatConnection(invocationConfig, "application/json");
        String requestJson = GsonUtil.toJson(payload);
        try {
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestJson.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }

            int statusCode = connection.getResponseCode();
            String responseBody = this.readResponseBody(connection, statusCode >= 400);
            if (statusCode >= 400) {
                throw new IOException("LLM request failed, status: " + statusCode + ", body: " + responseBody);
            }
            Type responseType = new TypeToken<Map<String, Object>>() {
            }.getType();
            Map<String, Object> response = GsonUtil.fromJson(responseBody, responseType);
            log.debug("LLM response received, status: {}", statusCode);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 从聊天补全响应中提取 assistant 文本，兼容字符串和内容片段数组两种格式。
     *
     * @param response 大模型接口完整响应
     * @return assistant 返回文本
     */
    private String extractAssistantContent(Map<String, Object> response) {
        Object choicesObject = response.get("choices");
        if (!(choicesObject instanceof List<?> choices) || choices.isEmpty()) {
            return "";
        }
        Object firstChoice = choices.getFirst();
        if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
            return "";
        }
        Object messageObject = choiceMap.get("message");
        if (!(messageObject instanceof Map<?, ?> messageMap)) {
            return "";
        }
        Object contentObject = messageMap.get("content");
        if (contentObject == null) {
            return "";
        }
        if (contentObject instanceof String content) {
            return content;
        }
        if (contentObject instanceof List<?> contentList) {
            StringBuilder builder = new StringBuilder();
            for (Object item : contentList) {
                if (item instanceof Map<?, ?> itemMap) {
                    Object textObject = itemMap.get("text");
                    if (textObject instanceof String text) {
                        builder.append(text);
                    }
                }
            }
            return builder.toString();
        }
        return String.valueOf(contentObject);
    }

    /**
     * 按响应状态读取正常流或错误流，并统一使用 UTF-8 解码。
     *
     * @param connection HTTP 连接对象
     * @param error 是否读取错误流
     * @return 响应体文本
     * @throws IOException 读取失败
     */
    private String readResponseBody(HttpURLConnection connection, boolean error) throws IOException {
        InputStream inputStream = error ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream == null) {
            return "";
        }
        try (InputStream stream = inputStream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void requireInvocationConfig(LlmInvocationConfig invocationConfig) {
        if (invocationConfig == null) {
            throw new IllegalArgumentException("LLM invocation config is required");
        }
        this.requireText(invocationConfig.baseUrl(), "baseUrl");
        this.requireText(invocationConfig.apiKey(), "apiKey");
        this.requireText(invocationConfig.model(), "model");
    }

    /**
     * 根据员工密钥主动读取模型列表并选择第一个可用模型。
     *
     * @param targetBaseUrl 员工 NewAPI 基础地址
     * @param targetApiKey 员工专属 API Key
     * @return 当前模型服务返回的第一个模型 ID
     */
    private String resolveFirstAvailableModel(String targetBaseUrl, String targetApiKey) {
        List<String> availableModels = this.fetchModelList(targetBaseUrl, targetApiKey);
        if (availableModels == null || availableModels.isEmpty()) {
            throw new IllegalStateException("Employee LLM model list is empty, cannot resolve default model");
        }
        return this.normalizeText(availableModels.getFirst());
    }

    /**
     * 归一化地址，避免后续拼接接口路径时出现双斜杠。
     *
     * @param rawBaseUrl 原始地址
     * @return 去除首尾空白和尾部斜杠后的地址
     */
    private String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = this.normalizeText(rawBaseUrl);
        if (normalized.endsWith("/")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 归一化文本，统一处理 null 和空白值。
     *
     * @param text 原始文本
     * @return 去除首尾空白后的文本，null 时返回空字符串
     */
    private String normalizeText(String text) {
        return text == null ? "" : text.trim();
    }

    /**
     * 读取必填文本字段，缺失时直接抛错，避免以半初始化状态运行。
     *
     * @param text 原始文本
     * @param fieldLabel 字段名
     * @return 去空白后的文本
     */
    private String requireText(String text, String fieldLabel) {
        String normalized = this.normalizeText(text);
        if (normalized.isBlank()) {
            throw new IllegalStateException("LLM config missing required field: " + fieldLabel);
        }
        return normalized;
    }
}
