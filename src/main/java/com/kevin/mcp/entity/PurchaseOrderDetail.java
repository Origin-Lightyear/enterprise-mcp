package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * 采购订单明细表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "采购订单明细表")
public class PurchaseOrderDetail {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 采购订单主表ID
     */
    @PrivateMcpToolParam(description = "采购订单主表ID")
    private Long poId;

    /**
     * 商品SKU ID
     */
    @PrivateMcpToolParam(description = "商品SKU ID")
    private Long skuId;

    /**
     * 采购数量
     */
    @PrivateMcpToolParam(description = "采购数量")
    private Integer qty;

    /**
     * 采购单价（含税）
     */
    @PrivateMcpToolParam(description = "采购单价（含税）")
    private BigDecimal price;

    /**
     * 行金额（数量*单价）
     */
    @PrivateMcpToolParam(description = "行金额（数量*单价）")
    private BigDecimal amount;

    /**
     * 累计已收货数量
     */
    @PrivateMcpToolParam(description = "累计已收货数量")
    private Integer receivedQty;

    /**
     * 行备注
     */
    @PrivateMcpToolParam(description = "行备注")
    private String remark;
}
