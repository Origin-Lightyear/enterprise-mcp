package com.kevin.mcp.agent.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定客户 Runtime 的实例身份，用于云端计划版本和租户绑定。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "enterprise.runtime")
public class RuntimeIdentityProperties {

    /** 租户唯一编号。 */
    private String tenantId = "";

    /** 客户环境中的 Runtime 实例编号。 */
    private String instanceId = "";

    /** 当前客户 Runtime 构建产物的发布版本。 */
    private String artifactVersion = "dev";

    /** 可选的 Runtime 构建产物 SHA-256 期望摘要；packaged 模式用于校验自动计算值。 */
    private String artifactDigest = "";
}
