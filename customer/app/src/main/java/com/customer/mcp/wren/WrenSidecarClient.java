package com.customer.mcp.wren;

import com.kevin.mcp.catalog.CurrentMdlAdapter;
import com.kevin.mcp.catalog.PreparedMdl;
import com.kevin.mcp.execution.SemanticModelSnapshot;
import com.kevin.mcp.execution.SemanticModelSnapshotProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 通过本机内部 RPC 调用 Wren Adapter，不暴露 Wren MCP 协议给外部 Agent。
 *
 * @author Kevin
 * @date 2026-09-12
 */
@Component
public class WrenSidecarClient implements SemanticModelSnapshotProvider, CurrentMdlAdapter {

    /** 保存 Wren 运行配置。 */
    private final WrenProperties properties;

    /** 调用 Wren 查询和管理接口。 */
    private final RestClient restClient;

    /** 调用 Wren 短超时健康探测接口。 */
    private final RestClient healthRestClient;

    /**
     * 创建 Wren 本机客户端，并沿用客户配置的超时边界。
     *
     * @param properties Wren 配置
     */
    public WrenSidecarClient(WrenProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .build();

        JdkClientHttpRequestFactory healthRequestFactory = new JdkClientHttpRequestFactory(httpClient);
        healthRequestFactory.setReadTimeout(properties.getHealthTimeout());
        this.healthRestClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(healthRequestFactory)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * 检查 Wren Adapter 是否可用。
     *
     * @return true 表示 Adapter 返回成功响应
     */
    public boolean isHealthy() {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            healthRestClient.get().uri(properties.getHealthPath()).retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 判断当前部署是否存在有效 Current MDL。
     *
     * @return 配置启用且 Current MDL 有效时返回 true
     */
    @Override
    public boolean isAvailable() {
        return current().isPresent();
    }

    /**
     * 执行一次客户侧 Wren 查询。
     *
     * @param request 请求级查询参数；员工模型配置只能由 customerapp 注入，不得持久化到 Wren profile
     * @return Wren 返回的结构化结果
     * @throws IllegalStateException Wren 未启用或调用失败
     */
    public Map<?, ?> query(Map<String, ?> request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Wren is not enabled");
        }
        try {
            Map<?, ?> response = restClient.post()
                    .uri(properties.getQueryPath())
                    .body(request == null ? Map.of() : request)
                    .retrieve()
                    .body(Map.class);
            return response == null ? Map.of() : response;
        } catch (RestClientResponseException exception) {
            throw new WrenQueryClientException(
                    exception.getStatusCode().value(), exception.getResponseBodyAsString(), exception);
        }
    }

    /**
     * 读取当前 Wren MDL 与摘要，并绑定为一次 Runtime 请求快照。
     *
     * @return 当前 MDL 快照
     */
    @Override
    public SemanticModelSnapshot snapshot() {
        return current().orElseThrow(() -> new IllegalStateException("Wren Current MDL is not configured"));
    }

    @Override
    public Optional<SemanticModelSnapshot> current() {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        Map<?, ?> response = restClient.get().uri("/internal/context").retrieve().body(Map.class);
        Map<?, ?> data = responseData(response, "Wren context response is invalid");
        if (!Boolean.TRUE.equals(data.get("configured"))) {
            return Optional.empty();
        }
        return Optional.of(snapshotFromData(data));
    }

    @Override
    public PreparedMdl prepare(String profileName) {
        Map<?, ?> response = adminExchange(HttpMethod.POST, "/internal/admin/mdl-candidates",
                Map.of("profileName", profileName));
        Map<?, ?> data = responseData(response, "Wren candidate response is invalid");
        Object token = data.get("candidateToken");
        if (!(token instanceof String candidateToken) || !candidateToken.matches("[a-f0-9]{32}")) {
            throw new IllegalStateException("Wren candidate token is invalid");
        }
        return new PreparedMdl(candidateToken, snapshotFromData(data));
    }

    @Override
    public SemanticModelSnapshot commit(String candidateToken, String expectedMdlDigest) {
        validateCandidateToken(candidateToken);
        Map<?, ?> response = adminExchange(HttpMethod.POST,
                "/internal/admin/mdl-candidates/" + candidateToken + "/commit",
                Map.of("expectedMdlDigest", expectedMdlDigest == null ? "" : expectedMdlDigest));
        return snapshotFromData(responseData(response, "Wren commit response is invalid"));
    }

    @Override
    public void discard(String candidateToken) {
        validateCandidateToken(candidateToken);
        adminExchange(HttpMethod.DELETE, "/internal/admin/mdl-candidates/" + candidateToken, null);
    }

    /** 获取客户实例当前已配置的数据源摘要。 */
    public Map<?, ?> datasources() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Wren is not enabled");
        }
        return restClient.get().uri("/internal/datasources").retrieve().body(Map.class);
    }

    /** 管理客户实例的数据源 profile；凭据应使用环境变量占位符。 */
    public Map<?, ?> manageDatasource(Map<String, ?> request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Wren is not enabled");
        }
        return restClient.post().uri("/internal/datasources").body(request).retrieve().body(Map.class);
    }

    /** 校验 SQL 计划而不访问数据库。 */
    public Map<?, ?> dryPlan(Map<String, ?> request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Wren is not enabled");
        }
        return restClient.post().uri("/internal/dry-plan").body(request).retrieve().body(Map.class);
    }

    /** 校验 SQL 是否可在客户数据库执行而不返回结果。 */
    public Map<?, ?> dryRun(Map<String, ?> request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Wren is not enabled");
        }
        return restClient.post().uri("/internal/dry-run").body(request).retrieve().body(Map.class);
    }

    /** 重新构建客户语义模型上下文。 */
    public Map<?, ?> rebuildContext() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Wren is not enabled");
        }
        return restClient.post().uri("/internal/context/rebuild").body(Map.of()).retrieve().body(Map.class);
    }

    /**
     * 调用 Adapter 管理接口并注入仅限 loopback 的 service token。
     *
     * @param method HTTP 方法
     * @param path Adapter 管理路径
     * @param request 请求体；GET/DELETE 可为空
     * @return Adapter 稳定响应
     */
    public Map<?, ?> adminExchange(HttpMethod method, String path, Object request) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Wren is not enabled");
        }
        try {
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(path)
                    .header("X-Wren-Service-Token", properties.getAdapterServiceToken());
            Map<?, ?> response = request == null
                    ? spec.retrieve().body(Map.class)
                    : spec.body(request).retrieve().body(Map.class);
            return response == null ? Map.of() : response;
        } catch (RestClientResponseException exception) {
            throw new WrenAdminClientException(exception.getStatusCode().value(), exception.getResponseBodyAsString(), exception);
        }
    }

    /** 下载当前 Wren 数据源与 MDL 的受保护备份。 */
    public byte[] downloadBackup() {
        try {
            return restClient.get().uri("/internal/admin/backup")
                    .header("X-Wren-Service-Token", properties.getAdapterServiceToken())
                    .retrieve().body(byte[].class);
        } catch (RestClientResponseException exception) {
            throw new WrenAdminClientException(exception.getStatusCode().value(), exception.getResponseBodyAsString(), exception);
        }
    }

    /** 上传并恢复已校验的 Wren 备份。 */
    public Map<?, ?> restoreBackup(byte[] backup) {
        try {
            Map<?, ?> response = restClient.post().uri("/internal/admin/restore")
                    .header("X-Wren-Service-Token", properties.getAdapterServiceToken())
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(backup).retrieve().body(Map.class);
            return response == null ? Map.of() : response;
        } catch (RestClientResponseException exception) {
            throw new WrenAdminClientException(exception.getStatusCode().value(), exception.getResponseBodyAsString(), exception);
        }
    }

    private Map<?, ?> responseData(Map<?, ?> response, String message) {
        if (response == null || !(response.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException(message);
        }
        return data;
    }

    private SemanticModelSnapshot snapshotFromData(Map<?, ?> data) {
        if (!(data.get("mdlDigest") instanceof String digest) || digest.isBlank()
                || !(data.get("manifest") instanceof Map<?, ?> manifest) || manifest.isEmpty()) {
            throw new IllegalStateException("Wren MDL snapshot is invalid");
        }
        LinkedHashMap<String, Object> normalizedManifest = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : manifest.entrySet()) {
            normalizedManifest.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return new SemanticModelSnapshot(digest, normalizedManifest);
    }

    private void validateCandidateToken(String candidateToken) {
        if (candidateToken == null || !candidateToken.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Wren candidate token is invalid");
        }
    }

    /** 表示 Adapter 管理接口返回的安全业务错误。 */
    public static final class WrenAdminClientException extends RuntimeException {
        private final int status;
        private final String responseBody;

        private WrenAdminClientException(int status, String responseBody, Throwable cause) {
            super("Wren admin request failed, HTTP status: " + status, cause);
            this.status = status;
            this.responseBody = responseBody;
        }

        /** @return Adapter HTTP 状态码 */
        public int getStatus() {
            return status;
        }

        /** @return Adapter 已脱敏的结构化响应 */
        public String getResponseBody() {
            return responseBody;
        }
    }

    /**
     * 表示 Adapter 查询接口返回的本地诊断错误。
     *
     * @author Kevin
     * @date 2026-09-20
     */
    public static final class WrenQueryClientException extends RuntimeException {
        private final int status;
        private final String responseBody;

        private WrenQueryClientException(int status, String responseBody, Throwable cause) {
            super("Wren query failed, HTTP status: " + status + ", responseBody=" + responseBody, cause);
            this.status = status;
            this.responseBody = responseBody;
        }

        /**
         * 返回 Adapter HTTP 状态码。
         *
         * @return Adapter HTTP 状态码
         */
        public int getStatus() {
            return status;
        }

        /**
         * 返回 Adapter 已脱敏的结构化错误响应。
         *
         * @return 包含 requestId 的 Adapter 错误响应
         */
        public String getResponseBody() {
            return responseBody;
        }
    }
}
