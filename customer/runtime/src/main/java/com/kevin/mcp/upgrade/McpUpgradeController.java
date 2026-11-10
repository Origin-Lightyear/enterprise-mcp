package com.kevin.mcp.upgrade;

import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.security.TenantMcpAuthentication;
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

    /** 统一校验 Tenant 发起升级请求时携带的实例 Token。 */
    private final TenantMcpAuthentication authentication;
    private final String updaterCommand;
    private final String instanceId;

    /**
     * 注入升级入口所需的固定运行参数。
     *
     * @param authentication Tenant MCP 通信认证模块
     * @param updaterCommand updater 绝对路径
     * @param identityProperties Runtime 身份配置，升级和注册共用实例标识
     */
    public McpUpgradeController(TenantMcpAuthentication authentication,
                                @Value("${enterprise.upgrade.command:/usr/local/libexec/enterprise-mcp-updater.sh}") String updaterCommand,
                                RuntimeIdentityProperties identityProperties) {
        this.authentication = authentication;
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
    public void upgrade(@RequestHeader(TenantMcpAuthentication.TOKEN_HEADER) String token,
                        @RequestBody McpUpgradeRequest request) throws IOException {
        authentication.verify(token);
        if (instanceId.isBlank() || request == null || blank(request.downloadUrl())
                || blank(request.version()) || blank(request.sha256())) {
            throw new IllegalArgumentException("升级参数不完整");
        }
        new ProcessBuilder(List.of(updaterCommand,
                        "--download-url", request.downloadUrl(), "--version", request.version(),
                        "--sha256", request.sha256()))
                .redirectErrorStream(true)
                .start();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
