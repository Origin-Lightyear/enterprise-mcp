package com.kevin.mcp.upgrade;

import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 提供受控的 MCP 升级入口，仅允许 Tenant 后端通过服务间 token 调用。
 *
 * @author Kevin
 * @date 2026-08-29
 */
@RestController
@RequestMapping("/internal/upgrades")
public class McpUpgradeController {

    private final String upgradeToken;
    private final String updaterCommand;
    private final String instanceId;

    /**
     * 注入升级入口所需的固定运行参数。
     *
     * @param upgradeToken 服务间升级 token
     * @param updaterCommand updater 绝对路径
     * @param identityProperties Runtime 身份配置，升级和注册共用实例标识
     */
    public McpUpgradeController(@Value("${enterprise.upgrade.token:}") String upgradeToken,
                                @Value("${enterprise.upgrade.command:/usr/local/libexec/enterprise-mcp-updater.sh}") String updaterCommand,
                                RuntimeIdentityProperties identityProperties) {
        this.upgradeToken = upgradeToken;
        this.updaterCommand = updaterCommand;
        this.instanceId = identityProperties.getInstanceId();
    }

    /**
     * 校验服务间 token 并异步启动固定 updater，避免主进程直接替换自身运行文件。
     *
     * @param token Tenant 服务间 token
     * @param request 升级请求
     * @throws IOException updater 进程无法启动
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void upgrade(@RequestHeader("X-MCP-Upgrade-Token") String token,
                        @RequestBody McpUpgradeRequest request) throws IOException {
        if (upgradeToken.isBlank() || !upgradeToken.equals(token)) {
            throw new IllegalArgumentException("升级 token 无效");
        }
        if (instanceId.isBlank() || request == null || blank(request.downloadUrl())
                || blank(request.version()) || blank(request.sha256())) {
            throw new IllegalArgumentException("升级参数不完整");
        }
        new ProcessBuilder(List.of(updaterCommand, "--instance-id", instanceId,
                        "--download-url", request.downloadUrl(), "--version", request.version(),
                        "--sha256", request.sha256()))
                .redirectErrorStream(true)
                .start();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
