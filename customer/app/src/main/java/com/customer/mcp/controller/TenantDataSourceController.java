package com.customer.mcp.controller;

import com.customer.mcp.k3.client.K3Properties;
import com.customer.mcp.wren.WrenProperties;
import com.customer.mcp.wren.WrenSidecarClient;
import com.kevin.mcp.security.TenantMcpAuthentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向 Tenant 后端提供当前 Runtime 可配置权限的数据源清单。
 *
 * @author Kevin
 * @date 2026-10-26
 */
@RestController
@RequestMapping("/internal/tenant/data-sources")
public class TenantDataSourceController {

    /** 统一校验 Tenant 读取数据源目录时携带的实例 Token。 */
    private final TenantMcpAuthentication authentication;
    /** 判断 Wren 数据源目录是否启用。 */
    private final WrenProperties wrenProperties;
    /** 读取 Current MDL 和数据源显示元数据。 */
    private final WrenSidecarClient wrenSidecarClient;
    /** 提供金蝶 API 数据源的稳定标识与显示名称。 */
    private final K3Properties k3Properties;

    /**
     * 注入服务认证、Wren 当前模型和金蝶数据源配置。
     *
     * @param authentication Tenant MCP 通信认证模块
     * @param wrenProperties Wren 配置
     * @param wrenSidecarClient Wren 本机客户端
     * @param k3Properties 金蝶数据源配置
     */
    public TenantDataSourceController(TenantMcpAuthentication authentication,
                                      WrenProperties wrenProperties,
                                      WrenSidecarClient wrenSidecarClient,
                                      K3Properties k3Properties) {
        this.authentication = authentication;
        this.wrenProperties = wrenProperties;
        this.wrenSidecarClient = wrenSidecarClient;
        this.k3Properties = k3Properties;
    }

    /**
     * 校验 Tenant 服务身份并返回数据库与 API 数据源描述。
     *
     * @param suppliedToken Tenant 服务间 Token
     * @return 当前 Runtime 数据源清单
     */
    @GetMapping
    public List<DataSourceDescriptor> list(
            @RequestHeader(TenantMcpAuthentication.TOKEN_HEADER) String suppliedToken) {
        authentication.verify(suppliedToken);
        List<DataSourceDescriptor> result = new ArrayList<>();
        if (wrenProperties.isEnabled()) {
            wrenSidecarClient.current().ifPresent(snapshot -> {
                Map<String, String> displayNames = wrenSidecarClient.datasourceDisplayNames();
                Object rawSources = snapshot.manifest().get("sources");
                if (rawSources instanceof Map<?, ?> sources) {
                    for (Object sourceId : sources.keySet()) {
                        String value = String.valueOf(sourceId);
                        result.add(new DataSourceDescriptor(value, displayNames.getOrDefault(value, value), "DATABASE",
                                List.of()));
                    }
                }
            });
        }
        if (k3Properties.isEnabled()) {
            result.add(new DataSourceDescriptor(k3Properties.getSourceId(), k3Properties.getDisplayName(), "API",
                    List.of("UNIQUE_IDENTIFIER")));
        }
        return List.copyOf(result);
    }

    /**
     * 描述 Tenant 管理员可识别的数据源及其支持能力。
     *
     * @param sourceId 数据源稳定标识
     * @param displayName 数据源显示名称
     * @param sourceType 数据源类型
     * @param supportedCredentialTypes 支持的 API 凭证类型
     */
    public record DataSourceDescriptor(String sourceId, String displayName, String sourceType,
                                       List<String> supportedCredentialTypes) {
    }
}
