package com.customer.mcp.wren;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 绑定客户侧 Wren 运行时配置。
 *
 * @author Kevin
 * @date 2026-09-12
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "enterprise.wren")
public class WrenProperties {

    /** 是否启用 Wren 集成；未配置可执行文件时保持关闭，保证旧客户包可启动。 */
    private boolean enabled;

    /** Wren Adapter 的本机地址。 */
    private String baseUrl = "http://127.0.0.1:8091";

    /** Wren Adapter 健康检查路径。 */
    private String healthPath = "/internal/health";

    /** Wren Adapter 单次健康检查超时时间。 */
    private Duration healthTimeout = Duration.ofSeconds(2);

    /** Wren Adapter 查询路径。 */
    private String queryPath = "/internal/query";

    /** Wren Adapter 连接超时时间。 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Wren Adapter 查询读取超时时间；首次请求可能需要构建大规模 Schema 向量索引。 */
    private Duration readTimeout = Duration.ofMinutes(5);

    /** Wren Adapter 可执行文件路径；为空时不由 customerapp 托管子进程。 */
    private String executable;

    /** Wren 项目根目录，包含建模内容、短期候选和唯一 Current MDL。 */
    private String projectRoot;

    /** Wren 实例目录，保存该客户独立的 profiles、缓存和运行状态。 */
    private String instanceDir;

    /** customerapp 调用 Adapter 管理接口时使用的 loopback service token。 */
    private String adapterServiceToken;

    /** 管理员首次登录时使用的共享 Token；为空时关闭管理入口。 */
    private String adminToken;

    /** 管理 Session 无操作过期时间。 */
    private Duration adminSessionTimeout = Duration.ofMinutes(30);

    /** 是否为管理 Session Cookie 增加 Secure 属性；生产 HTTPS 环境必须开启。 */
    private boolean adminSecureCookie;

    /** Wren Adapter 启动后允许等待健康检查的时间。 */
    private Duration startupTimeout = Duration.ofSeconds(30);
}
