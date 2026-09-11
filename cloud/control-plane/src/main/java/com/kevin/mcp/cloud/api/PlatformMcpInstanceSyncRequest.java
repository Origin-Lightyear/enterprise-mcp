package com.kevin.mcp.cloud.api;

import java.util.List;

/** 承载 Platform 下发的租户 MCP 期望实例快照。
 *
 * @author Kevin
 * @date 2026-09-11
 */
public record PlatformMcpInstanceSyncRequest(String tenantId, List<Item> servers) {
    /** 承载单个 MCP 的业务身份和期望版本。 */
    public record Item(Long platformMcpId, String instanceId, String name, String latestVersion,
                       String releaseNotes, String downloadUrl, String fileHash) {
    }
}
