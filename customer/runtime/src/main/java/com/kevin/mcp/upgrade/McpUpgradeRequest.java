package com.kevin.mcp.upgrade;

/**
 * 承载 Tenant 发起的 MCP 版本升级请求。
 *
 * @author Kevin
 * @date 2026-08-29
 */
public record McpUpgradeRequest(String downloadUrl, String version, String sha256) {
}
