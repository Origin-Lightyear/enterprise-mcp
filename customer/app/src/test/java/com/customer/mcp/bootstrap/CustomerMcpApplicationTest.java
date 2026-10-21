package com.customer.mcp.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.support.AopUtils;
import com.customer.mcp.tool.publicmcp.K3ExploreTool;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证客户 Fat JAR 的 Spring Bean 依赖可以完整装配。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@SpringBootTest(properties = {
        "enterprise.wren.enabled=false",
        "enterprise.debug.enabled=false",
        "enterprise.k3.enabled=true",
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

    @Autowired
    private K3ExploreTool exploreTool;

    /** 验证应用上下文能够启动。 */
    @Test
    void contextLoads() {
        assertTrue(AopUtils.isAopProxy(exploreTool));
        assertThrows(SecurityException.class, () -> exploreTool.searchForms("采购订单", null));
    }
}
