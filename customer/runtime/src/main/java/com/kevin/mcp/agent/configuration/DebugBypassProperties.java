package com.kevin.mcp.agent.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 承载 CustomerApp 调试旁路开关，便于无签名 Agent 联调真实 Tenant 身份链路。
 *
 * <p>开关开启时会跳过 Agent 来源签名、员工权限审查、结果脱敏和审计，仅保留计划绑定、
 * 本地只读能力和租户状态等机械护栏；因此只能用于受控调试环境，生产必须保持默认关闭。</p>
 *
 * @author Kevin
 * @date 2026-10-15
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "enterprise.debug")
public class DebugBypassProperties {

    /** 是否启用调试旁路；默认关闭时不改变任何现有安全行为。 */
    private boolean enabled = false;

    /** 调试请求复用的 Tenant 真实员工编号；启用开关时必须填写正数。 */
    private Long employeeId;
}


