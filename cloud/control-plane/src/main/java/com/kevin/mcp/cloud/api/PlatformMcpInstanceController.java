package com.kevin.mcp.cloud.api;

import com.kevin.mcp.cloud.catalog.PlatformMcpInstanceService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 暴露 Platform 到 Control Plane 的 MCP 期望实例同步入口。
 *
 * @author Kevin
 * @date 2026-09-11
 */
@RestController
@Profile("control-plane")
@RequestMapping("/control-plane/platform-mcp-instances")
public class PlatformMcpInstanceController {
    private final PlatformMcpInstanceService service;

    /** 注入实例同步服务。 */
    public PlatformMcpInstanceController(PlatformMcpInstanceService service) { this.service = service; }

    /** 接收 Platform 的租户全量快照。 */
    @PostMapping("/sync")
    public void sync(@RequestBody PlatformMcpInstanceSyncRequest request) { service.sync(request); }
}
