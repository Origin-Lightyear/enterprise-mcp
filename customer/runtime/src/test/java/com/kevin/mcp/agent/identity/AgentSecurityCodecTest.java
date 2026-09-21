package com.kevin.mcp.agent.identity;

import com.google.gson.JsonObject;
import com.kevin.mcp.agent.configuration.AgentSecurityProperties;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Runtime 对 Platform 签名、实例绑定和凭证时间窗口的校验。
 *
 * @author Kevin
 * @date 2026-09-19
 */
class AgentSecurityCodecTest {

    @Test
    void shouldVerifyCredentialBoundToRuntimeInstanceAndRejectTampering() throws Exception {
        KeyPair keyPair = generateKeyPair();
        AgentSecurityProperties properties = new AgentSecurityProperties();
        properties.setAgentPublicKeyPem(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        properties.setAgentPublicKeyFingerprint(AgentSecurityCodec.sha256Fingerprint(keyPair.getPublic().getEncoded()));
        AgentSecurityCodec codec = new AgentSecurityCodec(properties);
        String tenantHeader = tenantHeader();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String expiresAt = String.valueOf(Long.parseLong(timestamp) + 60L);
        String nonce = "nonce-1";
        String signature = sign(keyPair, codec.buildPayload(tenantHeader, "runtime-1", timestamp, expiresAt, nonce));

        assertThat(codec.verifySignature(tenantHeader, "runtime-1", timestamp, expiresAt, nonce, signature)).isTrue();
        assertThat(codec.verifySignature(tenantHeader, "runtime-2", timestamp, expiresAt, nonce, signature)).isFalse();
        assertThat(codec.parseTenantHeader(tenantHeader).mcpInstanceId()).isEqualTo("runtime-1");
        assertThat(codec.isCredentialTimeAllowed(Long.parseLong(timestamp), Long.parseLong(expiresAt), 60L)).isTrue();
        assertThat(codec.isCredentialTimeAllowed(Long.parseLong(timestamp), Long.parseLong(expiresAt) + 1L, 60L))
                .isFalse();
    }

    private String tenantHeader() {
        JsonObject json = new JsonObject();
        json.addProperty("tenantId", "10");
        json.addProperty("employeeId", "20");
        json.addProperty("versionNumber", 7);
        json.addProperty("mcpInstanceId", "runtime-1");
        return new Base32().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String sign(KeyPair keyPair, String payload) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
