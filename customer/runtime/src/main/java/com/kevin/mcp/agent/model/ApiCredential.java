package com.kevin.mcp.agent.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 承载 Tenant 为当前员工解析的第三方 API 身份凭证。
 *
 * @param credentialType 凭证类型
 * @param credential 凭证字段
 * @param updateTime 凭证更新时间
 * @author Kevin
 * @date 2026-10-26
 */
public record ApiCredential(String credentialType, Map<String, String> credential, LocalDateTime updateTime) {

    /**
     * 防止调用方修改请求级凭证字段。
     */
    public ApiCredential {
        credential = credential == null ? Map.of() : Map.copyOf(credential);
    }
}
