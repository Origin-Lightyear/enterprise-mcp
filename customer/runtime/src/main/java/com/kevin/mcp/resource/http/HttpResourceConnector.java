package com.kevin.mcp.resource.http;

import com.kevin.mcp.resource.ResourceType;
import com.kevin.mcp.resource.configuration.ResourceProfileProperties;
import com.kevin.mcp.resource.secret.SecretProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 通过资源编号调用客户内网 HTTP API。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Component
public class HttpResourceConnector {

    private final ResourceProfileProperties properties;
    private final SecretProvider secretProvider;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();
    private final HttpRequestGuard requestGuard = new HttpRequestGuard();

    /**
     * 注入多资源连接配置。
     *
     * @param properties 资源配置
     */
    public HttpResourceConnector(ResourceProfileProperties properties, SecretProvider secretProvider) {
        this.properties = properties;
        this.secretProvider = secretProvider;
    }

    /**
     * 调用指定 HTTP 资源的 GET 接口。
     *
     * @param resourceProfileId HTTP 资源编号
     * @param path API 相对路径
     * @param query 查询参数
     * @return 下游 JSON 响应
     */
    public Map<?, ?> get(String resourceProfileId, String path, Map<String, ?> query) {
        ResourceProfileProperties.Profile profile = properties.getProfiles().get(resourceProfileId);
        if (profile == null || profile.getType() != ResourceType.HTTP || profile.getUrl() == null) {
            throw new IllegalArgumentException("HTTP resource is not configured: " + resourceProfileId);
        }
        RestClient client = clients.computeIfAbsent(resourceProfileId, id -> createClient(profile));
        requestGuard.before(resourceProfileId, profile);
        int attempts = Math.max(0, Math.min(3, profile.getRetryAttempts()));
        for (int attempt = 0; ; attempt++) {
            try {
                var retryRequest = client.get();
                applyAuthentication(retryRequest, profile);
                Map<?, ?> response = retryRequest.uri(uriBuilder -> {
                            uriBuilder.path(path);
                            if (query != null) query.forEach((key, value) -> uriBuilder.queryParam(key, value));
                            return uriBuilder.build();
                        }).retrieve().body(Map.class);
                requestGuard.success(resourceProfileId);
                return response;
            } catch (ResourceAccessException exception) {
                requestGuard.failure(resourceProfileId, profile);
                if (attempt >= attempts) throw exception;
                sleepBeforeRetry(profile, attempt);
            } catch (RestClientResponseException exception) {
                requestGuard.failure(resourceProfileId, profile);
                if (exception.getStatusCode().is4xxClientError() || attempt >= attempts) throw exception;
                sleepBeforeRetry(profile, attempt);
            }
        }
    }

    /**
     * 调用指定 HTTP 资源的 JSON POST 接口。
     * 写请求必须显式携带幂等键，避免网络重试造成下游重复写入。
     *
     * @param resourceProfileId HTTP 资源编号
     * @param path API 相对路径
     * @param body JSON 请求体
     * @param idempotencyKey 幂等键
     * @return 下游 JSON 响应
     */
    public Map<?, ?> postJson(String resourceProfileId, String path, Map<String, ?> body, String idempotencyKey) {
        ResourceProfileProperties.Profile profile = properties.getProfiles().get(resourceProfileId);
        if (profile == null || profile.getType() != ResourceType.HTTP || profile.getUrl() == null) {
            throw new IllegalArgumentException("HTTP resource is not configured: " + resourceProfileId);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required for HTTP POST");
        }
        RestClient client = clients.computeIfAbsent(resourceProfileId, id -> createClient(profile));
        requestGuard.before(resourceProfileId, profile);
        var request = client.post().uri(path).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey).body(Objects.requireNonNullElse(body, Map.of()));
        applyAuthentication(request, profile);
        try {
            Map<?, ?> response = request.retrieve().body(Map.class);
            requestGuard.success(resourceProfileId);
            return response;
        } catch (RuntimeException exception) {
            requestGuard.failure(resourceProfileId, profile);
            throw exception;
        }
    }

    private RestClient createClient(ResourceProfileProperties.Profile profile) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(profile.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(profile.getReadTimeout());
        return RestClient.builder().baseUrl(profile.getUrl()).requestFactory(requestFactory).build();
    }

    private void applyAuthentication(RestClient.RequestHeadersSpec<?> request,
                                     ResourceProfileProperties.Profile profile) {
        String value = secretProvider.resolve(profile.getSecretRef()).orElse(profile.getAuthValue());
        if (value == null || value.isBlank()) {
            return;
        }
        switch (String.valueOf(profile.getAuthType()).toUpperCase()) {
            case "BEARER" -> request.header("Authorization", "Bearer " + value);
            case "API_KEY" -> request.header(profile.getAuthHeader(), value);
            case "BASIC" -> request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((String.valueOf(profile.getUsername()) + ":" + value)
                            .getBytes(StandardCharsets.UTF_8)));
            default -> { }
        }
    }

    private void sleepBeforeRetry(ResourceProfileProperties.Profile profile, int attempt) {
        try {
            Thread.sleep(Math.max(0L, profile.getRetryBackoff().toMillis()) * (attempt + 1L));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP retry interrupted", exception);
        }
    }
}
