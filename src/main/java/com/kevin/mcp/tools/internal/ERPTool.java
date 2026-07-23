package com.kevin.mcp.tools.internal;

import com.kevin.mcp.annotation.PrivateMcpTool;
import com.kevin.mcp.annotation.PrivateMcpToolParam;
import com.kevin.mcp.entity.ErpOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 提供内部 ERP 领域相关的私有 MCP Tool。
 *
 * @author Kevin
 * 2026/7/21
 */
@Component
public class ERPTool {
    private static final Logger log = LoggerFactory.getLogger(ERPTool.class);

    /**
     * 查询订单信息。
     */
    @PrivateMcpTool(description = "查询订单")
    public ErpOrder getOrder(
            @PrivateMcpToolParam(description = "订单ID", required = true) String orderId
    ) {
        log.info("进来了{}", orderId);
        // 模拟数据
        return new ErpOrder("1234", "Kevin", "北京");
    }
}
