package com.kevin.mcp.agent.client;

import com.kevin.mcp.agent.configuration.PlanningGatewayProperties;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;

import java.util.Map;

/**
 * 调用云端 Planning Gateway 获取签名执行计划。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Component
public class PlanningGatewayClient {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final RestClient restClient;
    private final PlanningGatewayProperties properties;

    /**
     * 创建规划网关客户端，并将超时限制在 Runtime 配置内。
     *
     * @param properties 规划网关配置
     */
    public PlanningGatewayClient(PlanningGatewayProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * 根据客户 Runtime 当前版本和用户意图请求计划。
     *
     * @param request 规划请求参数
     * @return 云端执行计划 JSON
     */
    public String requestPlan(Map<String, Object> request) {
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            throw new IllegalStateException("Planning Gateway URL is not configured");
        }
        try {
            String plan = restClient.post()
                    .uri(properties.getUrl())
                    .body(request)
                    .retrieve()
                    .body(String.class);
            if (plan == null || plan.isBlank()) {
                throw new IllegalStateException("Planning Gateway returned an empty plan");
            }
            return plan;
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("Planning Gateway rejected plan request: HTTP "
                    + exception.getStatusCode().value() + ", response=" + responseBody(exception), exception);
        }
    }

    /**
     * 请求 Control Plane 独立确认不可变 Catalog Snapshot。
     *
     * @param candidate Catalog 候选
     * @return 云端确认结果
     */
    public Map<?, ?> confirmCatalog(Map<String, Object> candidate) {
        return exchangeCatalog(properties.catalogConfirmationUrl(), candidate, false);
    }

    /**
     * 更新 Runtime 已在本地提交的当前 Catalog 引用。
     *
     * @param reference Runtime 当前引用
     * @return 云端保存结果
     */
    public Map<?, ?> referenceCatalog(Map<String, Object> reference) {
        return exchangeCatalog(properties.catalogReferenceUrl(), reference, true);
    }

    /**
     * 上传客户 Runtime 筛选后的结构知识快照。
     *
     * @param snapshot 带身份绑定和摘要的知识快照
     * @return Control Plane 确认响应
     */
    public Map<?, ?> uploadKnowledge(Map<String, Object> snapshot) {
        return exchangeCatalog(properties.knowledgeSnapshotUrl(), snapshot, false);
    }

    private Map<?, ?> exchangeCatalog(String url, Map<String, Object> payload, boolean put) {
        if (properties.getUrl() == null || properties.getUrl().isBlank()) {
            throw new IllegalStateException("Planning Gateway URL is not configured");
        }
        try {
            RestClient.RequestBodySpec request = put ? restClient.put().uri(url) : restClient.post().uri(url);
            Map<?, ?> response = request.body(payload).retrieve().body(Map.class);
            return response == null ? Map.of() : response;
        } catch (RestClientResponseException exception) {
            throw new CatalogRequestException(exception.getStatusCode().value(), catalogErrorMessage(exception), exception);
        }
    }

    /** 仅提取云端结构化业务说明，网关 HTML 和服务器异常正文不进入管理页面。 */
    private String catalogErrorMessage(RestClientResponseException exception) {
        String message = "Control Plane rejected Catalog request: HTTP " + exception.getStatusCode().value();
        if (!exception.getStatusCode().is4xxClientError()) {
            return message;
        }
        try {
            JsonElement root = JsonParser.parseString(exception.getResponseBodyAsString());
            if (root.isJsonObject()) {
                JsonObject error = root.getAsJsonObject();
                JsonElement code = error.get("code");
                JsonElement detail = error.get("message");
                if (code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isString()
                        && code.getAsString().matches("INVALID_REQUEST|STATE_CONFLICT|BINDING_REJECTED")
                        && detail != null && detail.isJsonPrimitive() && detail.getAsJsonPrimitive().isString()) {
                    String normalized = detail.getAsString().replaceAll("\\s+", " ").trim();
                    return message + " [" + code.getAsString() + "]: "
                            + normalized.substring(0, Math.min(normalized.length(), MAX_ERROR_MESSAGE_LENGTH));
                }
            }
        } catch (JsonParseException exceptionIgnored) {
            // 非标准响应只保留 HTTP 状态，原始响应仍由异常 cause 留供诊断。
        }
        return message;
    }

    /**
     * 区分云端明确拒绝与连接失败，并提供可展示的有限错误说明。
     *
     * @author Kevin
     * @date 2026-10-23
     */
    public static class CatalogRequestException extends IllegalStateException {

        /** 保存云端实际返回的 HTTP 状态。 */
        private final int statusCode;

        /**
         * 保留云端响应状态与底层异常。
         *
         * @param statusCode HTTP 状态
         * @param message 安全业务说明
         * @param cause 底层响应异常
         */
        public CatalogRequestException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        /**
         * 返回云端拒绝状态，供业务边界区分校验失败与服务故障。
         *
         * @return HTTP 状态
         */
        public int statusCode() {
            return statusCode;
        }
    }

    /**
     * 提取云端安全错误响应，避免仅显示状态码导致注册问题无法定位。
     *
     * @param exception HTTP 响应异常
     * @return 截断后的响应体，响应体为空时返回空标记
     */
    private String responseBody(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 2000 ? normalized : normalized.substring(0, 2000) + "...";
    }
}
