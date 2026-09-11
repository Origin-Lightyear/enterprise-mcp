package com.kevin.mcp.resource.configuration;

import com.kevin.mcp.resource.ResourceType;
import org.springframework.stereotype.Component;

/**
 * 在 Runtime 启动阶段校验客户多资源连接配置。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Component
public class ResourceProfileValidator {

    /** 校验所有资源的地址、超时和连接池边界。 */
    public ResourceProfileValidator(ResourceProfileProperties properties) {
        if (properties.getProfiles() == null) {
            throw new IllegalArgumentException("enterprise.resources.profiles must not be null");
        }
        properties.getProfiles().forEach((resourceId, profile) -> validate(resourceId, profile));
    }

    private void validate(String resourceId, ResourceProfileProperties.Profile profile) {
        if (resourceId == null || resourceId.isBlank() || profile == null) {
            throw new IllegalArgumentException("Resource profile id and value are required");
        }
        if (profile.getUrl() == null || profile.getUrl().isBlank()) {
            throw new IllegalArgumentException("Resource URL is required: " + resourceId);
        }
        if (profile.getConnectTimeout().isNegative() || profile.getReadTimeout().isNegative()) {
            throw new IllegalArgumentException("Resource timeout must not be negative: " + resourceId);
        }
        if (profile.getType() == ResourceType.JDBC && profile.getMaxPoolSize() < 1) {
            throw new IllegalArgumentException("JDBC maxPoolSize must be positive: " + resourceId);
        }
        if (profile.getType() == ResourceType.JDBC && profile.getMaxRows() < 1) {
            throw new IllegalArgumentException("JDBC maxRows must be positive: " + resourceId);
        }
        if (profile.getRetryAttempts() < 0 || profile.getRetryAttempts() > 3) {
            throw new IllegalArgumentException("HTTP retryAttempts must be between 0 and 3: " + resourceId);
        }
        if (profile.getRateLimitPerSecond() < 0 || profile.getCircuitFailureThreshold() < 0
                || profile.getCircuitOpenDuration().isNegative() || profile.getCircuitOpenDuration().isZero()) {
            throw new IllegalArgumentException("HTTP resilience settings are invalid: " + resourceId);
        }
    }
}
