package com.customer.mcp.tool;

import com.kevin.mcp.tools.CommonInternalTool;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 暴露当前客户的企业内部系统聚合查询 MCP Tool。
 *
 * <p>不同客户可以在本包新增或替换 {@code @McpTool}，并复用 Runtime 提供的公共安全执行流程。</p>
 *
 * @author Kevin
 * @date 2026-09-10
 */
@Component
public class CustomerInternalMcpTool {

    private final CommonInternalTool commonInternalTool;

    /**
     * 注入 Runtime 公共 MCP Tool 执行门面。
     *
     * @param commonInternalTool 公共安全执行门面
     */
    public CustomerInternalMcpTool(CommonInternalTool commonInternalTool) {
        this.commonInternalTool = commonInternalTool;
    }

    /**
     * 查询当前客户接入的企业内部系统数据。
     *
     * @param message 员工自然语言请求
     * @param requestContext MCP 请求上下文
     * @return 查询结果或权限拒绝提示
     */
    @McpTool(
            name = "linko_ent",
            description = "查询企业内部各系统数据，如报表、销售、客户、订单、门店、员工、部门、财务、产品等数据",
            generateOutputSchema = true
    )
    public Map<Boolean, String> linkoEnt(
            @McpToolParam(description = "查询内容") String message,
            McpSyncRequestContext requestContext
    ) {
        return commonInternalTool.execute(message, requestContext);
    }
}
