package com.kevin.mcp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 管理当前 Runtime 实例与 Tenant 通信时唯一使用的实例 Token。
 *
 * <p>该 Token 只代表当前租户与当前 Runtime 实例，统一用于升级、数据源目录和员工 API 凭证链路。</p>
 *
 * @author Kevin
 * @date 2026-10-27
 */
@Component
public class TenantMcpAuthentication {

    /** Tenant 与 Runtime 统一使用的通信 Token 请求头。 */
    public static final String TOKEN_HEADER = "X-Tenant-Mcp-Token";

    /** 当前租户和 Runtime 实例唯一使用的通信 Token。 */
    private final String runtimeToken;

    /**
     * 校验并装载当前 Runtime 实例的通信 Token。
     *
     * @param runtimeToken 由 Tenant 根密钥为当前租户和实例派生的 Token
     * @throws IllegalStateException Token 未配置
     */
    public TenantMcpAuthentication(@Value("${enterprise.tenant-mcp-token:}") String runtimeToken) {
        if (runtimeToken == null || runtimeToken.isBlank()) {
            throw new IllegalStateException("TENANT_MCP_TOKEN（当前实例通信 Token）未配置；"
                    + "请由可信运维人员使用 Tenant 根密钥、租户编号和 Runtime 实例编号生成");
        }
        String normalizedToken = runtimeToken.trim();
        byte[] tokenBytes;
        try {
            tokenBytes = Base64.getUrlDecoder().decode(normalizedToken);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("TENANT_MCP_TOKEN（当前实例通信 Token）格式无效；"
                    + "必须使用项目派生脚本生成 32 字节 Base64URL 无填充 Token", exception);
        }
        String canonicalToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        if (tokenBytes.length != 32 || !canonicalToken.equals(normalizedToken)) {
            throw new IllegalStateException("TENANT_MCP_TOKEN（当前实例通信 Token）格式无效；"
                    + "必须使用项目派生脚本生成 32 字节 Base64URL 无填充 Token");
        }
        this.runtimeToken = normalizedToken;
    }

    /**
     * 返回 Runtime 向 Tenant 发起内部请求时携带的实例 Token。
     *
     * @return 当前实例通信 Token
     */
    public String runtimeToken() {
        return runtimeToken;
    }

    /**
     * 使用常量时间比较校验 Tenant 发起的内部请求。
     *
     * @param suppliedToken 请求携带的实例 Token
     * @throws SecurityException Token 缺失或不匹配
     */
    public void verify(String suppliedToken) {
        byte[] expected = runtimeToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new SecurityException("Tenant MCP 通信身份校验失败");
        }
    }
}
