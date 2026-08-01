package com.kevin.mcp.processor;

import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.agent.connector.entity.TenantConfig;
import com.kevin.mcp.util.GsonUtil;
import jakarta.annotation.PostConstruct;
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
 * 处理大模型请求，并强制使用 Platform 下发的租户模型配置完成初始化。
 *
 * @author Kevin
 * @date 2026-07-20
 */
@Component
public class LLMProcessor {

    private static final Logger log = LoggerFactory.getLogger(LLMProcessor.class);

    /**
     * 保存当前租户生效的大模型服务地址。
     */
    private volatile String baseUrl = "";

    /**
     * 保存当前租户生效的默认模型名称。
     */
    private volatile String model = "";

    /**
     * 保存当前租户生效的系统提示词模板。
     */
    private volatile String prompt = "";

    /**
     * 保存当前租户生效的访问密钥。
     */
    private volatile String apiKey = "";

    /**
     * 标记是否已经完成基于 Platform 租户配置的初始化，避免在配置未就绪时误发请求。
     */
    private volatile boolean initialized;

    /**
     * 记录组件创建完成。真正的模型初始化必须等待 Platform 租户配置下发。
     */
    @PostConstruct
    private void init() {
        log.info("LLMProcessor 已创建，等待 Platform 租户配置初始化");
    }

    /**
     * 使用租户配置刷新运行期 LLM 地址、模型和密钥。
     * Platform 当前不返回模型名称，因此在地址和密钥就绪后主动查询模型列表并取第一个可用模型。
     *
     * @param tenantConfig 当前租户配置快照
     */
    public synchronized void refreshTenantConfig(TenantConfig tenantConfig) {
        if (tenantConfig == null) {
            throw new IllegalStateException("Platform tenant config is null");
        }
        String tenantBaseUrl = this.normalizeBaseUrl(this.requireText(tenantConfig.llmUrl(), "llmUrl"));
        String tenantApiKey = this.normalizeText(this.requireText(tenantConfig.llmKey(), "llmKey"));
        String tenantModel = this.resolveFirstAvailableModel(tenantBaseUrl, tenantApiKey);
        boolean changed = !tenantBaseUrl.equals(this.baseUrl)
                || !tenantApiKey.equals(this.apiKey)
                || !tenantModel.equals(this.model)
                || !this.initialized;

        // 候选连接验证成功后再整体发布，避免业务线程观察到地址、密钥和模型不匹配的中间状态。
        this.baseUrl = tenantBaseUrl;
        this.apiKey = tenantApiKey;
        this.model = tenantModel;
        this.prompt = "";
        this.initialized = true;

        if (!changed) {
            return;
        }
        log.info("已按租户配置刷新 LLM 连接信息，当前 url: {}, model: {}", this.baseUrl, this.model);
        this.performStartupHealthCheck();
    }

    /**
     * 判断指定 Platform 配置是否已经应用到当前 LLM 连接。
     *
     * @param tenantConfig Platform 租户配置
     * @return 地址和密钥是否已成功应用
     */
    public boolean isTenantConfigApplied(TenantConfig tenantConfig) {
        if (tenantConfig == null || !this.initialized) {
            return false;
        }
        return this.normalizeBaseUrl(tenantConfig.llmUrl()).equals(this.baseUrl)
                && this.normalizeText(tenantConfig.llmKey()).equals(this.apiKey);
    }

    /**
     * 启动或配置刷新后执行健康检查，只记录日志，不阻断调用方自定义异常处理。
     */
    private void performStartupHealthCheck() {
        this.ensureInitialized();
        List<String> availableModels = this.fetchModelList();
        if (availableModels == null) {
            log.warn("LLM 健康检查失败：无法连接大模型服务 {}", this.baseUrl);
            return;
        }
        log.info("LLM 连接正常，服务 {} 可用模型共 {} 个: {}", this.baseUrl, availableModels.size(), availableModels);
        this.verifyModelAvailability(availableModels);
    }

    /**
     * 查询大模型服务的模型列表，兼容 OpenAI 风格的 GET /v1/models。
     *
     * @return 可用模型 ID 列表；连接失败时返回 null
     */
    private List<String> fetchModelList() {
        return this.fetchModelList(this.baseUrl, this.apiKey);
    }

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
     * 打开模型列表接口连接。健康检查使用较短超时，避免刷新配置时长时间阻塞。
     *
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
     * 校验当前默认模型是否存在于模型服务返回列表中。
     *
     * @param availableModels 模型服务可用模型列表
     */
    private void verifyModelAvailability(List<String> availableModels) {
        if (availableModels.contains(this.model)) {
            log.info("LLM 模型校验通过：配置模型 {} 存在于可用列表中", this.model);
            return;
        }
        log.warn("LLM 模型校验未通过：配置模型 {} 不在可用列表中，可用模型: {}", this.model, availableModels);
    }

    /**
     * 打开聊天补全接口连接并设置通用请求头。
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
        connection.setRequestProperty("Authorization", "Bearer " + this.apiKey);
        return connection;
    }

    /**
     * 使用默认模型执行一次普通非流式对话。
     *
     * @param userPrompt 用户输入内容
     * @return 模型返回文本
     * @throws IOException 请求失败
     */
    public String chat(String userPrompt) throws IOException {
        return this.chat(this.model, this.prompt, userPrompt, BigDecimal.valueOf(0.7D), false);
    }

    /**
     * 返回当前默认模型名称，供审计和日志复用。
     *
     * @return 默认模型名称
     */
    public String getModel() {
        this.ensureInitialized();
        return this.model;
    }

    /**
     * 使用指定模型与提示词完成一次非流式对话。
     *
     * @param modelName 本次调用使用的模型名称
     * @param systemPrompt 本次调用附带的系统提示词
     * @param userPrompt 用户输入内容
     * @param temperature 采样温度
     * @param enableThinking 是否启用推理模式
     * @return 模型返回文本
     * @throws IOException 请求失败
     */
    public String chat(String modelName, String systemPrompt, String userPrompt, BigDecimal temperature,
                       boolean enableThinking) throws IOException {
        Map<String, Object> payload = this.buildChatPayloadWithSystemPrompt(modelName, systemPrompt, userPrompt, false,
                temperature, enableThinking);
        Map<String, Object> response = this.executeChatCompletion(payload);
        return this.extractAssistantContent(response);
    }

    /**
     * 使用默认模型并返回完整响应结构，供需要读取 usage 等字段的扩展场景使用。
     *
     * @param userPrompt 用户输入内容
     * @return 大模型接口完整响应
     * @throws IOException 请求失败
     */
    public Map<String, Object> chatForResponse(String userPrompt) throws IOException {
        Map<String, Object> payload = this.buildChatPayloadWithSystemPrompt(this.model, this.prompt, userPrompt, false,
                BigDecimal.valueOf(0.7D), false);
        return this.executeChatCompletion(payload);
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
        this.ensureInitialized();
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
     * 发送聊天补全请求，并统一解析响应 JSON。
     *
     * @param payload 请求体
     * @return 响应结构
     * @throws IOException 请求失败
     */
    private Map<String, Object> executeChatCompletion(Map<String, Object> payload) throws IOException {
        this.ensureInitialized();
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

    /**
     * 确保当前处理器已经完成 Platform 配置初始化，避免在配置缺失时误发请求。
     */
    private void ensureInitialized() {
        if (!this.initialized) {
            throw new IllegalStateException("LLMProcessor has not been initialized from Platform tenant config");
        }
    }

    /**
     * 当 Platform 未下发模型名时，主动从模型服务读取列表并选择第一个可用模型。
     *
     * @return 当前模型服务返回的第一个模型 ID
     */
    private String resolveFirstAvailableModel(String targetBaseUrl, String targetApiKey) {
        List<String> availableModels = this.fetchModelList(targetBaseUrl, targetApiKey);
        if (availableModels == null || availableModels.isEmpty()) {
            throw new IllegalStateException("LLM model list is empty, cannot resolve default model from Platform config");
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
            throw new IllegalStateException("Platform tenant config missing required field: " + fieldLabel);
        }
        return normalized;
    }
}
