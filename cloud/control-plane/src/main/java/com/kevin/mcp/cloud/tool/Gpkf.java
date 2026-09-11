package com.kevin.mcp.cloud.tool;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 计算 Agent RSA 公钥 DER 内容的 SHA-256 指纹。
 *
 * <p>工具不保存任何客户公钥；调用时传入 Base64 DER 或完整 PEM 文本，可用于生成客户 Runtime
 * 的 {@code SAAS_AGENT_PUBLIC_KEY_FINGERPRINT} 配置。</p>
 *
 * @author Kevin
 * @date 2026-07-30
 */
public final class Gpkf {

    private static final String PUBLIC_KEY_BEGIN_MARKER = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_KEY_END_MARKER = "-----END PUBLIC KEY-----";

    private Gpkf() {
    }

    /**
     * 从第一个命令行参数读取公钥并输出 SHA-256 指纹。
     *
     * @param args 第一个参数为 Base64 DER 或完整 PEM 公钥
     */
    public static void main(String[] args) {
        System.out.println(sha256Fingerprint("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAogJqkFl3+FH+9pydLF9pvc8oMABY5va8EKZLa/sEVOODMWwLUP8ZOBELDsxZMdFolXAmmxYSkWGZ6ox5R688qqdtYE2sP2pkjKNVMmE+2YBUixFJzpQNjJOIKgE5uMkRH+tIVzq9y7PGzzx1PG9GZD/BS0lA2P+Uv/yJRXHJ3hc2BM9lF+LIVUzBno1eRDHYQKVujqd6WJHNjVYPPZRu0DoczauLHmNFq8TvR50BMz2KWdRYdmtW7UJSF+8zm4/UUYtYkpqreC0QXhe4eC7LULBZ/TZci5rDJFTN0oQ11lTC4HIF2GdBEWh4pZeAPWyUUyEETYlvI6vVxQ+Dh2KJXQIDAQAB"));
    }

    /**
     * 计算 RSA 公钥的 SHA-256 十六进制指纹。
     *
     * @param publicKeyPem Base64 DER 或完整 PEM 公钥
     * @return 小写 SHA-256 十六进制指纹
     */
    public static String sha256Fingerprint(String publicKeyPem) {
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            throw new IllegalArgumentException("Public key is required");
        }
        String normalized = publicKeyPem.replace(PUBLIC_KEY_BEGIN_MARKER, "")
                .replace(PUBLIC_KEY_END_MARKER, "")
                .replaceAll("\\s+", "")
                .strip();
        byte[] encodedKey;
        try {
            encodedKey = Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Public key is not valid Base64 or PEM", exception);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedKey);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to calculate public key fingerprint", exception);
        }
    }
}
