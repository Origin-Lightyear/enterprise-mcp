package com.customer.mcp.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动客户专属 Runtime，并同时扫描公共 Runtime 与客户能力实现。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@SpringBootApplication(scanBasePackages = {"com.kevin.mcp", "com.customer.mcp"}, excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
public class CustomerMcpApplication {

    /**
     * 启动客户单 JAR Runtime。
     *
     * @param args Spring Boot 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(CustomerMcpApplication.class, args);
    }
}
