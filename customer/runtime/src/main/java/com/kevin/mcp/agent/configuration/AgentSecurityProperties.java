package com.kevin.mcp.agent.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定当前客户 Agent 请求签名的公钥和指纹配置。
 *
 * @author Kevin
 * @date 2026-09-10
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "saas.verify")
public class AgentSecurityProperties {

    /** AgentServer RSA 公钥，支持 Base64 DER 或完整 PEM 文本。 */
    private String agentPublicKeyPem;

    /** RSA 公钥 DER 内容的 SHA-256 十六进制指纹。 */
    private String agentPublicKeyFingerprint;
}
