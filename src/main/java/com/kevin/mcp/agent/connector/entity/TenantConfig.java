package com.kevin.mcp.agent.connector.entity;

import java.time.LocalDateTime;

/**
 * 承接 Platform 内部租户配置接口的返回数据。
 *
 * @param id 租户 ID
 * @param llmKey LLM API 密钥
 * @param llmUrl LLM API URL
 * @param status 当前状态值
 * @param authEndTime 授权截止时间
 * @param version 版本号
 * @author Kevin
 * @date 2026-08-01
 */
public record TenantConfig(
        Long id,
        String llmKey,
        String llmUrl,
//        String mcpUrl,
//        String pubKey,
        Integer status,
        LocalDateTime authEndTime,
        Integer version) {
}
