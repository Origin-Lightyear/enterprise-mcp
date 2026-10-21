package com.kevin.mcp.agent.identity;

import com.kevin.mcp.agent.configuration.AgentSecurityProperties;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * 解析 AgentServer 请求头并验证签名。
 *
 * @author Kevin
 * @date 2026-07-29
 */
@Component
public class AgentSecurityCodec {
    private static final String PUBLIC_KEY_BEGIN_MARKER = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_KEY_END_MARKER = "-----END PUBLIC KEY-----";

    private final PublicKey publicKey;

    /**
     * 使用当前客户配置的 AgentServer 公钥构建安全编解码器。
     *
     * <p>启动时校验公钥指纹，防止部署时误用其他客户的 AgentServer 公钥。</p>
     *
     * @param properties 当前客户 Agent 签名配置
     */
    public AgentSecurityCodec(AgentSecurityProperties properties) {
        if (properties == null || isBlank(properties.getAgentPublicKeyPem())
                || isBlank(properties.getAgentPublicKeyFingerprint())) {
            throw new IllegalStateException("Agent public key and fingerprint must be configured");
        }
        String normalized = normalizePublicKey(properties.getAgentPublicKeyPem());
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Agent public key is not valid Base64 or PEM", exception);
        }
        String actualFingerprint = sha256Fingerprint(decoded);
        if (!actualFingerprint.equalsIgnoreCase(properties.getAgentPublicKeyFingerprint().trim())) {
            throw new IllegalStateException("Agent public key fingerprint does not match configured key");
        }
        try {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
            publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Agent public key is not a valid RSA public key", exception);
        }
    }

    /**
     * 解析 Base32 编码的 Tenant 头，提取租户、员工和版本信息。
     *
     * @param tenantHeader Base32 编码后的租户上下文
     * @return 解析后的租户请求信息
     */
    public TenantRequestInfo parseTenantHeader(String tenantHeader) {
        byte[] decoded = new Base32().decode(tenantHeader);
        String json = new String(decoded, StandardCharsets.UTF_8);
        JsonObject tenantObject = JsonParser.parseString(json).getAsJsonObject();
        return new TenantRequestInfo(
                tenantObject.get("tenantId").getAsString(),
                tenantObject.get("employeeId").getAsLong(),
                tenantObject.get("versionNumber").getAsLong(),
                tenantObject.get("mcpInstanceId").getAsString()
        );
    }

    /**
     * 验证 Authorization 中的签名是否与当前请求头一致。
     *
     * @param tenantHeader Base32 编码后的租户上下文
     * @param mcpInstanceId 目标 Runtime 实例标识
     * @param timestamp 请求时间戳
     * @param expiresAt 凭证过期时间戳
     * @param nonce 请求随机数
     * @param signature Base64 编码后的签名结果
     * @return 验签是否通过
     */
    public boolean verifySignature(String tenantHeader, String mcpInstanceId, String timestamp, String expiresAt,
                                   String nonce, String signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(this.publicKey);
            verifier.update(buildPayload(tenantHeader, mcpInstanceId, timestamp, expiresAt, nonce)
                    .getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 校验时间戳是否处于允许窗口，避免旧签名在较长时间后再次进入系统。
     *
     * @param epochSeconds 请求时间戳
     * @param allowedSkewSeconds 允许偏差秒数
     * @return 是否处于允许窗口
     */
    public boolean isTimestampAllowed(long epochSeconds, long allowedSkewSeconds) {
        long delta = Math.abs(Instant.now(Clock.systemUTC()).getEpochSecond() - epochSeconds);
        return delta <= allowedSkewSeconds;
    }

    /**
     * 校验 Platform 签发时间和显式过期时间，拒绝超长或已过期凭证。
     *
     * @param issuedAtEpochSeconds 签发 Unix 秒时间戳
     * @param expiresAtEpochSeconds 过期 Unix 秒时间戳
     * @param maximumLifetimeSeconds Runtime 接受的最长凭证生命周期
     * @return 凭证时间窗口是否合法
     */
    public boolean isCredentialTimeAllowed(long issuedAtEpochSeconds, long expiresAtEpochSeconds,
                                           long maximumLifetimeSeconds) {
        long now = Instant.now(Clock.systemUTC()).getEpochSecond();
        return expiresAtEpochSeconds >= now
                && expiresAtEpochSeconds >= issuedAtEpochSeconds
                && expiresAtEpochSeconds - issuedAtEpochSeconds <= maximumLifetimeSeconds;
    }

    /**
     * 生成签名与验签共用的规范化原文，避免双方拼接顺序不一致。
     *
     * @param tenantHeader Base32 编码后的租户上下文
     * @param mcpInstanceId 目标 Runtime 实例标识
     * @param timestamp 请求时间戳
     * @param expiresAt 凭证过期时间戳
     * @param nonce 请求随机数
     * @return 规范化原文
     */
    public String buildPayload(String tenantHeader, String mcpInstanceId, String timestamp, String expiresAt,
                               String nonce) {
        return "Tenant=" + tenantHeader + "&McpInstanceId=" + mcpInstanceId + "&Timestamp=" + timestamp
                + "&ExpiresAt=" + expiresAt + "&Nonce=" + nonce;
    }

    static String sha256Fingerprint(byte[] encodedKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedKey);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to calculate key fingerprint", exception);
        }
    }

    private static String normalizePublicKey(String publicKeyPem) {
        return publicKeyPem.replace(PUBLIC_KEY_BEGIN_MARKER, "")
                .replace(PUBLIC_KEY_END_MARKER, "")
                .replaceAll("\\s+", "")
                .strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
