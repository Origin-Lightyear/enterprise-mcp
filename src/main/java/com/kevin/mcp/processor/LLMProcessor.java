package com.kevin.mcp.processor;

import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.util.GsonUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型处理器
 *
 * @author Kevin
 * 2026/7/20
 */
@Component
public class LLMProcessor {

    private static final Logger log = LoggerFactory.getLogger(LLMProcessor.class);

    /**
     * 保存大模型服务基础地址。
     * 统一在初始化阶段去掉末尾斜杠，避免后续多处拼接接口路径时出现双斜杠。
     */
    @Value("${llm.url}") String baseUrl;
    /**
     * 保存默认模型名称。
     * 通过集中配置默认值，避免调用方重复感知底层模型切换。
     */
    @Value("${llm.model}") String model;
    /**
     * 保存默认业务提示词模板。
     * 作为系统级上下文复用，保证不同调用入口输出口径一致。
     */
    @Value("${llm.prompt}") String prompt;
    /**
     * 保存访问大模型接口所需的认证密钥。
     * 仅在发起请求时透传到 Authorization 头，减少密钥分散使用带来的维护风险。
     */
    @Value("${llm.api-key}") String apiKey;

    /**
     * 依赖注入完成后对配置值做归一化处理。
     */
    @PostConstruct
    private void init() {
        if (baseUrl == null) {
            this.baseUrl = "";
        } else {
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        this.model = model == null ? "" : model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.prompt = prompt == null ? "" : prompt.trim();
        log.info("LLM 初始化, url: {}, model: {}", this.baseUrl, this.model);
        this.performStartupHealthCheck();
    }

    /**
     * 启动时对大模型服务做连通性与模型可用性检查。
     * 只记录告警日志，不阻断应用启动，避免网络抖动等临时问题导致服务无法拉起。
     */
    private void performStartupHealthCheck() {
        if (this.baseUrl.isBlank()) {
            log.warn("LLM 健康检查跳过：未配置 llm.base-url");
            return;
        }
        List<String> availableModels = this.fetchModelList();
        if (availableModels == null) {
            log.warn("LLM 健康检查失败：无法连接大模型服务 {}", this.baseUrl);
            return;
        }
        log.info("LLM 连接正常，服务 {} 可用模型共 {} 个: {}", this.baseUrl, availableModels.size(), availableModels);
        this.verifyModelAvailability(availableModels);
    }

    /**
     * 查询大模型服务器的模型列表。
     * 调用 OpenAI 兼容的 GET /v1/models 接口，返回所有可用模型的 ID 集合。
     * 任何异常都视为服务不可达，返回 null 由调用方决定后续处理。
     *
     * @return 可用模型 ID 列表，连接失败时返回 null
     */
    private List<String> fetchModelList() {
        HttpURLConnection connection = null;
        try {
            connection = this.openModelsConnection();
            int statusCode = connection.getResponseCode();
            String responseBody = this.readResponseBody(connection, statusCode >= 400);
            if (statusCode >= 400) {
                log.warn("LLM 模型列表查询失败，状态码: {}，响应: {}", statusCode, responseBody);
                return null;
            }
            Type responseType = new TypeToken<Map<String, Object>>() { }.getType();
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
     * 打开模型列表接口连接。
     * 健康检查使用较短的连接与读取超时，避免启动阶段长时间阻塞。
     *
     * @return HTTP 连接对象
     * @throws IOException 打开连接失败
     */
    private HttpURLConnection openModelsConnection() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(this.baseUrl + "/v1/models").toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        if (!this.apiKey.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + this.apiKey);
        }
        return connection;
    }

    /**
     * 校验配置的默认模型是否在大模型服务器的可用模型列表中。
     * 模型不存在时只记录告警，不阻断启动，便于在服务器模型更新前后仍能保持服务可用。
     *
     * @param availableModels 服务器返回的可用模型 ID 列表
     */
    private void verifyModelAvailability(List<String> availableModels) {
        if (this.model.isBlank()) {
            log.warn("LLM 健康检查跳过模型校验：未配置 llm.model");
            return;
        }
        boolean modelExists = availableModels.contains(this.model);
        if (modelExists) {
            log.info("LLM 模型校验通过：配置模型 {} 在可用列表中", this.model);
        } else {
            log.warn("LLM 模型校验未通过：配置模型 {} 不在可用列表中，可用模型: {}", this.model, availableModels);
        }
    }

    /**
     * 打开聊天接口连接并设置通用请求头。
     *
     * @param accept 响应内容类型
     * @return HTTP 连接对象
     * @throws IOException 打开连接失败
     */
    private HttpURLConnection openChatConnection(String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(this.baseUrl + "/v1/chat/completions").toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(300_000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", accept);
        if (!this.apiKey.isBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + this.apiKey);
        }
        return connection;
    }

    /**
     * 构造聊天请求体。
     *
     * @param modelName 模型名称
     * @param prompt 提示词
     * @param stream 是否流式
     * @param temperature 采样温度
     * @param enableThinking 是否启用思考
     * @return 对应 JSON 结构的 Map
     */
    private Map<String, Object> buildChatPayload(String modelName, String prompt, boolean stream, BigDecimal temperature, boolean enableThinking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelName);
        payload.put("stream", stream);
        payload.put("temperature", temperature);
        payload.put("enable_thinking", false);
        payload.put("chat_template_kwargs", Map.of("enable_thinking", enableThinking));
        payload.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        return payload;
    }

    /**
     * 调用默认模型完成一次非流式对话。
     * 统一复用配置中的业务提示词模板，适合只关心最终文本结果的普通场景。
     *
     * @param userPrompt 用户输入内容
     * @return 模型返回的文本内容
     * @throws IOException 网络请求失败或响应体读取失败
     */
    public String chat(String userPrompt) throws IOException {
        return this.chat(this.model, this.prompt, userPrompt, BigDecimal.valueOf(0.7D), false);
    }

    /**
     * 调用指定模型完成一次非流式对话。
     * 允许调用方覆盖默认系统提示词和采样参数，以兼顾公共封装与特定业务灵活性。
     *
     * @param modelName 本次调用使用的模型名称
     * @param systemPrompt 本次调用附带的系统提示词，为空时不注入 system 消息
     * @param userPrompt 用户输入内容
     * @param temperature 采样温度
     * @param enableThinking 是否启用推理模式
     * @return 模型返回的文本内容
     * @throws IOException 网络请求失败或响应体读取失败
     */
    public String chat(String modelName, String systemPrompt, String userPrompt, BigDecimal temperature, boolean enableThinking) throws IOException {
        Map<String, Object> payload = this.buildChatPayloadWithSystemPrompt(modelName, systemPrompt, userPrompt, false, temperature, enableThinking);
        Map<String, Object> response = this.executeChatCompletion(payload);
        return this.extractAssistantContent(response);
    }

    /**
     * 调用默认模型并返回完整响应结构。
     * 适合需要读取 usage、finish_reason 或原始 message 节点的扩展场景。
     *
     * @param userPrompt 用户输入内容
     * @return 大模型接口返回的完整 JSON 结构
     * @throws IOException 网络请求失败或响应体读取失败
     */
    public Map<String, Object> chatForResponse(String userPrompt) throws IOException {
        Map<String, Object> payload = this.buildChatPayloadWithSystemPrompt(this.model, this.prompt, userPrompt, false, BigDecimal.valueOf(0.7D), false);
        return this.executeChatCompletion(payload);
    }

    /**
     * 构造支持 system 消息的聊天请求体。
     * 将默认业务提示词放在 system 角色中，有利于约束模型行为而不污染用户原始输入。
     *
     * @param modelName 模型名称
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户输入
     * @param stream 是否流式返回
     * @param temperature 采样温度
     * @param enableThinking 是否启用推理模式
     * @return 对应 JSON 结构的 Map
     */
    private Map<String, Object> buildChatPayloadWithSystemPrompt(String modelName, String systemPrompt, String userPrompt, boolean stream, BigDecimal temperature, boolean enableThinking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelName == null || modelName.isBlank() ? this.model : modelName.trim());
        payload.put("stream", stream);
        payload.put("temperature", temperature == null ? BigDecimal.valueOf(0.7D) : temperature);
        payload.put("enable_thinking", false);
        payload.put("chat_template_kwargs", Map.of("enable_thinking", enableThinking));

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
     * 发送聊天补全请求并解析响应 JSON。
     * 统一在这里处理鉴权、错误码和序列化，避免上层业务重复编写样板 HTTP 代码。
     *
     * @param payload 聊天请求体
     * @return 解析后的响应结构
     * @throws IOException 网络请求失败或响应体读取失败
     */
    private Map<String, Object> executeChatCompletion(Map<String, Object> payload) throws IOException {
        HttpURLConnection connection = this.openChatConnection("application/json");
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
            Type responseType = new TypeToken<Map<String, Object>>() { }.getType();
            Map<String, Object> response = GsonUtil.fromJson(responseBody, responseType);
            log.debug("LLM response received, status: {}", statusCode);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 从聊天补全响应中提取 assistant 最终文本。
     * 这里兼容 OpenAI 风格的 choices/message/content 结构，减少业务层对响应格式的耦合。
     *
     * @param response 大模型接口返回的完整 JSON
     * @return assistant 返回文本
     */
    @SuppressWarnings("unchecked")
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
     * 按响应状态读取正常流或错误流。
     * 统一字符集为 UTF-8，避免模型返回中文时出现乱码。
     *
     * @param connection HTTP 连接对象
     * @param error 是否读取错误流
     * @return 响应体字符串
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
}
