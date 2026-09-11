package com.kevin.mcp.cloud.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 绑定 Control Plane 的员工模型解析、网络超时和执行计划签名配置。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "enterprise.control-plane")
public class ControlPlanePlanningProperties {

    /** Tenant 服务基础地址。 */
    private String tenantServiceBaseUrl;

    /** Tenant 员工 LLM 配置接口路径。 */
    private String employeeLlmConfigPath = "/api/v1/agent/inner/employee-llm-config";

    /** Tenant 服务连接超时。 */
    private Duration tenantConnectTimeout = Duration.ofSeconds(5);

    /** Tenant 服务读取超时。 */
    private Duration tenantReadTimeout = Duration.ofSeconds(15);

    /** 员工 LLM 配置硬过期时间。 */
    private Duration employeeLlmConfigCacheTtl = Duration.ofMinutes(5);

    /** 员工 LLM 配置缓存最大条目数。 */
    private int employeeLlmConfigCacheMaximumSize = 10000;

    /** 云端模型连接超时。 */
    private Duration llmConnectTimeout = Duration.ofSeconds(15);

    /** 云端模型读取超时。 */
    private Duration llmReadTimeout = Duration.ofMinutes(5);

}
