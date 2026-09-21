package com.customer.mcp.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 验证客户 Fat JAR 的 Spring Bean 依赖可以完整装配。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@SpringBootTest(properties = {
        "enterprise.wren.enabled=false",
        "enterprise.wren.admin-token=test-admin-token",
        "saas.verify.agent-public-key-pem="
                + "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAm1kqdjC5dglLfizpvid0axXg0v5S2+MmJ9BjC5b9OfCfM8RHDwCG"
                + "ASNZAc06bPZBX36Aij9sVUfI6rSPhq4hwNWGvH5g7qXBdOQm5X8qmVBPuDjABYgrrrLk7cwEUFRGvq6Wrad57J1Y2VLBK"
                + "mxK0LqsgKBL8VPXqToe0adSUbWJq3g2tnBzqNHFvwvpX0Npx6A8urq2ax7MgqWYilm/vaFRly7BNF2CUc95pOmuh9P6be0W"
                + "Ie0lt47DDA5Las+BqN8kv1y9Yn1W4z4bzempwtWtaKHDBe0noyH5q1/eBaTTq3WELapCjRg69Y4YqNH1lh3Xc3OgOuK/Ev"
                + "Cjh323sQIDAQAB",
        "saas.verify.agent-public-key-fingerprint=4403c89298534dc4545f646cc1c8afcf45d883d42782dc5a8bcef57429dfb5c0"
})
class CustomerMcpApplicationTest {

    /** 验证应用上下文能够启动。 */
    @Test
    void contextLoads() {
    }
}
