package com.kevin.mcp.agent.connector;

/**
 * 定义 MCPServer 本地租户配置的可用状态，供 HTTP 边界统一决定是否放行请求。
 *
 * @author Kevin
 * @date 2026-08-01
 */
public enum TenantAvailability {

    /** Platform 配置尚未成功同步，服务不能确认租户是否可用。 */
    NOT_INITIALIZED,

    /** 租户已启用且授权仍在有效期内。 */
    AVAILABLE,

    /** 租户已被 Platform 禁用。 */
    DISABLED,

    /** 租户授权已到期。 */
    EXPIRED
}
