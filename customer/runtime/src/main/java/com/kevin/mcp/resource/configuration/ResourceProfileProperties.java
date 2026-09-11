package com.kevin.mcp.resource.configuration;

import com.kevin.mcp.resource.ResourceType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 绑定客户 Runtime 的多资源连接配置。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "enterprise.resources")
public class ResourceProfileProperties {

    /** 按资源逻辑编号保存独立连接配置。 */
    private Map<String, Profile> profiles = new LinkedHashMap<>();

    /**
     * 描述单个数据库或 API 资源。
     *
     * @author Kevin
     * @date 2026-09-09
     */
    @Getter
    @Setter
    public static class Profile {

        /** 资源类型，默认使用 JDBC。 */
        private ResourceType type = ResourceType.JDBC;

        /** JDBC 厂商，用于选择方言和驱动检查。 */
        private String vendor;

        /** JDBC URL 或 API 基础地址。 */
        private String url;

        /** 可选数据库用户名；生产环境优先由 Secret Provider 注入。 */
        private String username;

        /** 可选数据库密码；禁止提交到源码仓库。 */
        private String password;

        /** Secret Provider 中的凭据引用。 */
        private String secretRef;

        /** 连接超时时间。 */
        private Duration connectTimeout = Duration.ofSeconds(3);

        /** 读取超时时间。 */
        private Duration readTimeout = Duration.ofSeconds(10);

        /** 连接池最大连接数。 */
        private int maxPoolSize = 20;

        /** 单次只读查询最大返回行数，避免计划意外拉取整库数据。 */
        private int maxRows = 1000;

        /** HTTP 认证类型：NONE、BEARER、API_KEY、BASIC。 */
        private String authType = "NONE";

        /** HTTP 认证密钥或令牌；生产环境应通过 secretRef 注入。 */
        private String authValue;

        /** API Key 请求头名称。 */
        private String authHeader = "X-API-Key";

        /** GET 请求最多重试次数，限制在 0-3 之间。 */
        private int retryAttempts = 2;

        /** 重试前等待时间。 */
        private Duration retryBackoff = Duration.ofMillis(200);

        /** 单资源每秒允许的最大请求数，0 表示不限制。 */
        private int rateLimitPerSecond;

        /** 连续失败多少次后打开熔断，0 表示不启用。 */
        private int circuitFailureThreshold;

        /** 熔断打开后等待多久再允许探测请求。 */
        private Duration circuitOpenDuration = Duration.ofSeconds(30);
    }
}
