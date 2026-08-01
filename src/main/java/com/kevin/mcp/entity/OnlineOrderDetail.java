package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * 线上订单明细表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "线上订单明细表")
public class OnlineOrderDetail {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 关联线上订单主表ID
     */
    @PrivateMcpToolParam(description = "关联线上订单主表ID")
    private Long orderId;

    /**
     * 商品SKU ID
     */
    @PrivateMcpToolParam(description = "商品SKU ID")
    private Long skuId;

    /**
     * 购买数量
     */
    @PrivateMcpToolParam(description = "购买数量")
    private Integer qty;

    /**
     * 成交单价
     */
    @PrivateMcpToolParam(description = "成交单价")
    private BigDecimal price;

    /**
     * 行总金额
     */
    @PrivateMcpToolParam(description = "行总金额")
    private BigDecimal totalAmount;
}
