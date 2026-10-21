package com.kevin.mcp.agent.client;

import com.kevin.mcp.agent.configuration.PlanningGatewayProperties;
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
            throw new IllegalStateException("Runtime Catalog request rejected: HTTP " + exception.getStatusCode().value()
                    + ", response=" + responseBody(exception), exception);
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
