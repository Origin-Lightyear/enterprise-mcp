package com.kevin.mcp.agent.connector.util;

import org.apache.commons.codec.binary.Base32;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.UUID;

/**
 * 演示如何构建 {@code verifySourceAndGetAuth} 所需的正确请求头。
 *
 * <p>鉴权 header 共四个：
 * <ol>
 *   <li><b>tenant</b> — JSON {@code {tenantId, employeeId, versionNumber}} 经 Base32 编码</li>
 *   <li><b>timestamp</b> — Unix epoch 秒数（服务端允许偏差默认 {@code 300}s）</li>
 *   <li><b>nonce</b> — 任意随机字符串，一次有效（防重放）</li>
 *   <li><b>authorization</b> — 签名原文 {@code "Tenant=<tenant>&Timestamp=<timestamp>&Nonce=<nonce>"} 的 RSA-SHA256 签名结果，Base64 编码</li>
 * </ol>
 *
 * <p><b>注意：</b>生产环境中私钥由 AgentServer 持有，本 Demo 为演示而自生成密钥对。
 * 若需通过真实服务端验签，必须将公钥配置到 {@code AgentSecurityCodec.AGENT_PUBLIC_KEY_PEM}。
 *
 * @author Kevin
 * @date 2026-07-30
 */
public class HeaderConstructionDemo {

    public static void main(String[] args) throws Exception {
        // ===== 1. 准备密钥对（生产环境中私钥由 AgentServer 持有） =====
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        System.out.println("=== 1. 生成 RSA 密钥对 ===");
        System.out.println("公钥 (Base64):\n" + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        System.out.println();

        // ===== 2. 构建 tenant header（Base32 编码的 JSON）=====
        String tenantId = "tenant-001";
        String employeeId = "emp-042";
        long versionNumber = 3L;

        String tenantJson = "{\"tenantId\":\"" + tenantId + "\",\"employeeId\":\"" + employeeId
                + "\",\"versionNumber\":" + versionNumber + "}";
        String tenantHeader = new Base32().encodeToString(tenantJson.getBytes(StandardCharsets.UTF_8));

        System.out.println("=== 2. 构建 tenant header ===");
        System.out.println("原始 JSON : " + tenantJson);
        System.out.println("Base32 编码: " + tenantHeader);
        System.out.println();

        // ===== 3. 生成时间戳和 nonce =====
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = UUID.randomUUID().toString().replace("-", "");

        System.out.println("=== 3. 时间戳 & Nonce ===");
        System.out.println("timestamp: " + timestamp);
        System.out.println("nonce    : " + nonce);
        System.out.println();

        // ===== 4. 构建签名原文并签名 =====
        // 签名原文格式必须与 buildPayload() 完全一致
        String payload = "Tenant=" + tenantHeader + "&Timestamp=" + timestamp + "&Nonce=" + nonce;

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = signer.sign();
        String authorization = Base64.getEncoder().encodeToString(signatureBytes);

        System.out.println("=== 4. 签名 ===");
        System.out.println("签名原文    : " + payload);
        System.out.println("authorization: " + authorization);
        System.out.println();

        // ===== 5. 输出完整请求头 =====
        System.out.println("=== 5. 最终 Headers（可直接用于 MCP 请求）===");
        System.out.println("authorization: " + authorization);
        System.out.println("tenant      : " + tenantHeader);
        System.out.println("timestamp   : " + timestamp);
        System.out.println("nonce       : " + nonce);
        System.out.println();

        // ===== 6. 本地验签验证（模拟服务端逻辑） =====
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(payload.getBytes(StandardCharsets.UTF_8));
        boolean verified = verifier.verify(Base64.getDecoder().decode(authorization));

        System.out.println("=== 6. 本地验签结果 ===");
        System.out.println("验签通过: " + verified);
    }
}
