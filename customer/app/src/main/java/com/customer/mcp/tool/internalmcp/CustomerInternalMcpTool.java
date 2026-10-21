package com.customer.mcp.tool.internalmcp;

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

    /** Agent 工具描述：列出当前客户可查询的 ERP 系统与数据域，供模型判断路由。 */
    private static final String LINKO_ENT_DESCRIPTION =
            "查询各ERP系统的数据,系统如下:"
                    + "依云系统:客户(订单, 订单商品, 回单, 回单明细, 回单结算, 调拨配送, 调拨明细, 三方平台订单, 客户地址, 客户账户, 客户类型, 客户区域, 门店欠结明细, 投诉记录) "
                    + "门店(门店区域, 门店交账, 交账明细, 库存) 商品(商品分类, 商品单位, 商品属性, 订单商品) 职员(订单, 回单) "
                    + "单据类型(所有业务单据) 三方平台订单(订单 通过唯一标识关联)"
                    + "水趣系统:企业/商户(客户(客户地址, 客户微信来源, 用户优惠券(优惠券编码), 电子票账户(电子票基本信息(电子票套餐)), 订单(订单商品)), "
                    + "门店(拼团商品(拼团分类), 拼团订单(拼团首图), 订单), 商品(订单商品, 拼团商品), "
                    + "优惠券活动(手机号领券活动(活动配置, 领取记录), 注册送券活动(活动配置), 优惠券编码(用户优惠券)), 电子票(电子票套餐, 电子票账户))";

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
            description = LINKO_ENT_DESCRIPTION,
            generateOutputSchema = true
    )
    public Map<Boolean, String> linkoEnt(
            @McpToolParam(description = "查询内容") String message,
            McpSyncRequestContext requestContext
    ) {
        return commonInternalTool.execute(message, requestContext);
    }
}

