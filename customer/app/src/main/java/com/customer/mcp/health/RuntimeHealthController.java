package com.customer.mcp.health;

import com.customer.mcp.wren.WrenProperties;
import com.customer.mcp.wren.WrenSidecarClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供客户 Runtime 就绪检查，并将已启用的 Wren Adapter 纳入健康判定。
 *
 * @author Kevin
 * @date 2026-09-18
 */
@RestController
public class RuntimeHealthController {

    /** 表示组件健康。 */
    private static final String STATUS_UP = "UP";

    /** 表示组件不可用。 */
    private static final String STATUS_DOWN = "DOWN";

    /** 表示组件已按配置关闭。 */
    private static final String STATUS_DISABLED = "DISABLED";

    /** 保存 Wren 运行配置。 */
    private final WrenProperties wrenProperties;

    /** 调用 Wren 本机客户端。 */
    private final WrenSidecarClient wrenClient;

    /**
     * 创建 Runtime 健康检查入口。
     *
     * @param wrenProperties Wren 配置
     * @param wrenClient Wren 本机客户端
     */
    public RuntimeHealthController(WrenProperties wrenProperties, WrenSidecarClient wrenClient) {
        this.wrenProperties = wrenProperties;
        this.wrenClient = wrenClient;
    }

    /**
     * 检查 Runtime 及已启用的 Wren Adapter 是否就绪。
     *
     * @return 全部必需组件就绪时返回 200，否则返回 503
     */
    @GetMapping("/internal/health")
    public ResponseEntity<HealthResponse> health() {
        boolean wrenEnabled = wrenProperties.isEnabled();
        boolean wrenHealthy = !wrenEnabled || wrenClient.isHealthy();
        String wrenStatus = !wrenEnabled ? STATUS_DISABLED : wrenHealthy ? STATUS_UP : STATUS_DOWN;
        String runtimeStatus = wrenHealthy ? STATUS_UP : STATUS_DOWN;

        HealthResponse response = new HealthResponse(runtimeStatus, Map.of(
                "runtime", new ComponentHealth(STATUS_UP, true),
                "wren", new ComponentHealth(wrenStatus, wrenEnabled)));
        HttpStatus httpStatus = wrenHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * 表示 Runtime 健康检查响应。
     *
     * @param status Runtime 聚合状态
     * @param components 组件健康状态
     */
    public record HealthResponse(String status, Map<String, ComponentHealth> components) { }

    /**
     * 表示单个组件的健康状态。
     *
     * @param status 组件状态
     * @param enabled 组件是否启用
     */
    public record ComponentHealth(String status, boolean enabled) { }
}
