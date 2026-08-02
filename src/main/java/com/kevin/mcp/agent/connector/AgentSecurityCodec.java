package com.kevin.mcp.agent.connector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * 解析 AgentServer 请求头并验证签名。
 * @author kevin
 */
@Component
public class AgentSecurityCodec {
    /** 公钥 PEM 文本 */
    private static final String AGENT_PUBLIC_KEY_PEM = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArxp+TtiRRZtX3ypCVN/sBvcHmlrFpwS6AXhh0muU1Yn1xz62x0iL8JXFKKdmR9Fk4dbD5TmyfgA26tUKZw873dMrAHX8EWBAoPbMngwroWXi1Zw33O48RR39rrjigw/dxuZHuy9qxrkxdOysY4FToppjFw2Ij2mRnZ3RnzhXq13jAND3yJnwCdvoPhLfImZX9evNG61hNeTAODYyTq09kkW87UA0rLG8DbIvi52UjI+BG5UZrGdBMcCEzgm4jLI6G6YmJIIW/DPk/s+S+TUqemtw9cLofzto1pZnN54zh32KNzAZN+0PUIAXMg0qvn9xakQBb4fD0BI8eP3nBbbevwIDAQAB";
    /** 预期公钥指纹 */
    private static final String AGENT_PUBLIC_KEY_FINGERPRINT = "085d065ef72e497cc571ce3e5d17ddc9d1442ac36e24f50e792c2d966b6a8633";

    private final PublicKey publicKey;

    /**
     * 构建安全编解码器
     * 使用程序内置公钥使用, 指定时钟, 避免时间窗测试依赖系统时钟。
     */
    AgentSecurityCodec() {
        try {
            String normalized = AGENT_PUBLIC_KEY_PEM.replaceAll("\\s+", "").strip();
            byte[] decoded = Base64.getDecoder().decode(normalized);
            String actualFingerprint = sha256Fingerprint(decoded);
            if (!actualFingerprint.equalsIgnoreCase(AGENT_PUBLIC_KEY_FINGERPRINT)) {
                throw new IllegalStateException("指纹不匹配");
            }
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
            publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);
        } catch (Exception exception) {
            // 无法初始化公钥
            throw new IllegalStateException("身份不明", exception);
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
                tenantObject.get("employeeId").getAsString(),
                tenantObject.get("versionNumber").getAsLong()
        );
    }

    /**
     * 验证 Authorization 中的签名是否与当前请求头一致。
     *
     * @param tenantHeader Base32 编码后的租户上下文
     * @param timestamp 请求时间戳
     * @param nonce 请求随机数
     * @param signature Base64 编码后的签名结果
     * @return 验签是否通过
     */
    public boolean verifySignature(String tenantHeader, String timestamp, String nonce, String signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(this.publicKey);
            verifier.update(buildPayload(tenantHeader, timestamp, nonce).getBytes(StandardCharsets.UTF_8));
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
     * 生成签名与验签共用的规范化原文，避免双方拼接顺序不一致。
     *
     * @param tenantHeader Base32 编码后的租户上下文
     * @param timestamp 请求时间戳
     * @param nonce 请求随机数
     * @return 规范化原文
     */
    public String buildPayload(String tenantHeader, String timestamp, String nonce) {
        return "Tenant=" + tenantHeader + "&Timestamp=" + timestamp + "&Nonce=" + nonce;
    }

    static String sha256Fingerprint(byte[] encodedKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedKey);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate key fingerprint", exception);
        }
    }
}
