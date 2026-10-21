package com.kevin.mcp.agent.identity;

import com.kevin.mcp.agent.configuration.DebugBypassProperties;
import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.model.EmployeeAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 提供调试旁路模式下的真实员工身份装载与开关一致性校验。
 *
 * <p>调试模式不伪造员工快照，而是每次请求实时读取 Tenant 员工数据；员工被禁用后，
 * 后续请求会失败关闭。租户固定使用 Runtime 已绑定租户，避免调试身份跨租户漂移。</p>
 *
 * @author Kevin
 * @date 2026-10-15
 */
@Component
public class DebugIdentityProvider {
    private static final Logger log = LoggerFactory.getLogger(DebugIdentityProvider.class);

    private final HttpAgentServerClient agentServerClient;
    private final DebugBypassProperties properties;
    private final String tenantId;

    /**
     * 注入 Tenant 客户端、调试配置和当前 Runtime 租户。
     *
     * @param agentServerClient Platform 与 Tenant 客户端
     * @param properties 调试旁路配置
     * @param tenantId Runtime 当前绑定租户
     */
    public DebugIdentityProvider(
            HttpAgentServerClient agentServerClient,
            DebugBypassProperties properties,
            @Value("${enterprise.runtime.tenant-id}") String tenantId
    ) {
        this.agentServerClient = agentServerClient;
        this.properties = properties;
        this.tenantId = tenantId;
    }

    /**
     * 校验调试配置并在启用时输出显式风险日志。
     */
    @PostConstruct
    public void validate() {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getEmployeeId() == null || properties.getEmployeeId() <= 0) {
            throw new IllegalStateException("enterprise.debug.employee-id is required when debug bypass is enabled");
        }
        log.warn("MCP debug bypass is ENABLED: identity source verification, permission review/masking, "
                + "and audit are disabled; tenantId={}, employeeId={}", tenantId, properties.getEmployeeId());
    }

    /**
     * 判断调试旁路是否启用。
     *
     * @return true 表示已启用调试旁路
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 返回调试旁路使用的租户编号。
     *
     * @return Runtime 当前绑定租户
     */
    public String tenantId() {
        return tenantId;
    }

    /**
     * 返回调试旁路使用的员工编号。
     *
     * @return 配置的真实员工编号
     */
    public Long employeeId() {
        return properties.getEmployeeId();
    }

    /**
     * 实时拉取并校验 Tenant 真实员工鉴权快照。
     *
     * @return 当前启用的真实员工身份
     * @throws IllegalStateException 员工不存在、返回无效或已被禁用
     */
    public VerifiedEmployee loadVerifiedEmployee() {
        Long employeeId = properties.getEmployeeId();
        EmployeeAuth employeeAuth = agentServerClient.fetchTenantAuth(tenantId, employeeId);
        if (employeeAuth == null || employeeAuth.employeeId() == null || employeeAuth.employeeId() <= 0) {
            throw new IllegalStateException("Debug employee auth snapshot is invalid");
        }
        if (!"ENABLED".equalsIgnoreCase(employeeAuth.status())) {
            throw new IllegalStateException("Debug employee is not enabled");
        }
        return new VerifiedEmployee(tenantId, employeeAuth);
    }
}



