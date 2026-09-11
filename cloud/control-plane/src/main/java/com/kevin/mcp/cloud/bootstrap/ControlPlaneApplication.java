package com.kevin.mcp.cloud.bootstrap;

import com.kevin.mcp.cloud.configuration.ControlPlanePlanningProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 启动独立的 Control Plane 应用。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@SpringBootApplication(scanBasePackages = "com.kevin.mcp.cloud")
@EnableConfigurationProperties(ControlPlanePlanningProperties.class)
public class ControlPlaneApplication {

    /**
     * 启动云端规划和目录服务。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ControlPlaneApplication.class);
        application.setAdditionalProfiles("control-plane");
        application.run(args);
    }
}
