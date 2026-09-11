package com.kevin.mcp.cloud.catalog;

/**
 * 保存客户 Runtime 启动后上报的实际部署摘要。
 *
 * @author Kevin
 * @date 2026-09-09
 *
 * @param tenantId 客户编号
 * @param instanceId Runtime 实例编号
 * @param artifactVersion 客户 JAR 版本
 * @param artifactDigest 客户 JAR 摘要
 * @param catalogJson Runtime 扫描得到的完整能力 Schema
 */
public record RuntimeRegistration(
        String tenantId,
        String instanceId,
        String artifactVersion,
        String artifactDigest,
        String catalogJson
) {

    /**
     * 固化 Runtime 注册载荷，避免调用方修改能力 Schema。
     *
     * @param tenantId 客户编号
     * @param instanceId 实例编号
     * @param artifactVersion JAR 版本
     * @param artifactDigest JAR 摘要
     */
    public RuntimeRegistration {
        catalogJson = catalogJson == null ? "" : catalogJson;
    }
}
