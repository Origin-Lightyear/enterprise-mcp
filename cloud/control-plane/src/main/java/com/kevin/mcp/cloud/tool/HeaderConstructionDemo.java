package com.kevin.mcp.cloud.tool;

import org.apache.commons.codec.binary.Base32;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

/**
 * 生成 Agent 请求签名联调所需的 RSA 密钥信息和请求头。
 *
 * <p>鉴权 Header 包含：</p>
 * <ol>
 *   <li>{@code tenant}：JSON {@code {tenantId, employeeId, versionNumber}} 的 Base32 编码；</li>
 *   <li>{@code timestamp}：Unix epoch 秒数；</li>
 *   <li>{@code nonce}：一次性随机字符串；</li>
 *   <li>{@code authorization}：规范化原文的 RSA-SHA256 签名 Base64。</li>
 * </ol>
 *
 * <p>该工具只用于云端开发或联调，运行时生成临时密钥对，不参与 Control Plane 在线规划流程。</p>
 *
 * @author Kevin
 * @date 2026-07-30
 */
public final class HeaderConstructionDemo {

    private static final int RSA_KEY_SIZE = 2048;

    private HeaderConstructionDemo() {
    }

    /**
     * 生成临时 RSA 密钥对并输出一组可用于联调的签名请求头。
     *
     * @param args 可选参数依次为 tenantId、employeeId、versionNumber
     * @throws Exception RSA 密钥生成、参数解析或签名失败
     */
    public static void main(String[] args) throws Exception {
        String tenantId = argument(args, 0, "tenant-001");
        String employeeId = argument(args, 1, "emp-042");
        long versionNumber = Long.parseLong(argument(args, 2, "3"));

        // 生成临时密钥对；真实环境私钥必须由 AgentServer 保存。
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_KEY_SIZE);
        KeyPair keyPair = generator.generateKeyPair();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        System.out.println("=== 1. RSA 公钥配置 ===");
        System.out.println("SAAS_AGENT_PUBLIC_KEY_PEM=" + publicKeyBase64);
        System.out.println("SAAS_AGENT_PUBLIC_KEY_FINGERPRINT=" + Gpkf.sha256Fingerprint(publicKeyBase64));
        System.out.println();

        String tenantJson = "{\"tenantId\":\"" + tenantId + "\",\"employeeId\":\"" + employeeId
                + "\",\"versionNumber\":" + versionNumber + "}";
        String tenantHeader = new Base32().encodeToString(tenantJson.getBytes(StandardCharsets.UTF_8));
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String payload = "Tenant=" + tenantHeader + "&Timestamp=" + timestamp + "&Nonce=" + nonce;

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String authorization = Base64.getEncoder().encodeToString(signer.sign());

        System.out.println("=== 2. 签名原文 ===");
        System.out.println(payload);
        System.out.println();
        System.out.println("=== 3. MCP 请求 Headers ===");
        System.out.println("authorization: " + authorization);
        System.out.println("tenant: " + tenantHeader);
        System.out.println("timestamp: " + timestamp);
        System.out.println("nonce: " + nonce);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(payload.getBytes(StandardCharsets.UTF_8));
        System.out.println("验签通过: " + verifier.verify(Base64.getDecoder().decode(authorization)));
    }

    private static String argument(String[] args, int index, String defaultValue) {
        if (args == null || args.length <= index || args[index] == null || args[index].isBlank()) {
            return defaultValue;
        }
        return args[index];
    }
}
