package com.kevin.mcp.agent.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 绑定云端 Planning Gateway 的访问配置。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "enterprise.planning-gateway")
public class PlanningGatewayProperties {

    /** 云端规划接口地址。 */
    private String url;

    /** Runtime 与云端连接超时时间。 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** Runtime 等待云端响应的最长时间。 */
    private Duration readTimeout = Duration.ofSeconds(15);

    /**
     * 根据固定 Control Plane API 约定生成 Runtime 注册地址。
     *
     * @return Runtime 注册接口地址
     * @throws IllegalStateException 规划地址为空或不符合固定路径约定
     */
    public String catalogConfirmationUrl() {
        return controlPlaneBaseUrl() + "/catalog-snapshots/confirm";
    }

    /**
     * 根据固定 Control Plane interface 生成 Runtime 当前 Catalog 引用地址。
     *
     * @return Runtime Catalog 引用接口地址
     * @throws IllegalStateException 规划地址为空或不符合固定路径约定
     */
    public String catalogReferenceUrl() {
        return controlPlaneBaseUrl() + "/runtime-catalog-references";
    }

    /**
     * 返回知识摘要上传接口地址。
     *
     * @return Control Plane 的知识快照入口
     */
    public String knowledgeSnapshotUrl() {
        return controlPlaneBaseUrl() + "/knowledge-snapshots";
    }

    private String controlPlaneBaseUrl() {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Planning Gateway URL is not configured");
        }
        String normalizedUrl = url.replaceAll("/+$", "");
        String suffix = "/control-plane/planning/plans";
        if (!normalizedUrl.endsWith(suffix)) {
            throw new IllegalStateException("Planning Gateway URL must end with " + suffix);
        }
        return normalizedUrl.substring(0, normalizedUrl.length() - "/planning/plans".length());
    }
}
