package com.kevin.mcp.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 Platform 租户配置轮询调度。
 *
 * @author Kevin
 * @date 2026-08-01
 */
@Configuration
@EnableScheduling
public class TenantConfigPollingConfiguration {
}
