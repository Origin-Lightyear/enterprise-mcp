package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * POS销售订单明细表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "POS销售订单明细表")
public class PosOrderDetail {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 关联POS订单主表ID
     */
    @PrivateMcpToolParam(description = "关联POS订单主表ID")
    private Long orderId;

    /**
     * 商品SKU ID
     */
    @PrivateMcpToolParam(description = "商品SKU ID")
    private Long skuId;

    /**
     * 销售数量
     */
    @PrivateMcpToolParam(description = "销售数量")
    private Integer qty;

    /**
     * 成交单价（折后价）
     */
    @PrivateMcpToolParam(description = "成交单价（折后价）")
    private BigDecimal price;

    /**
     * 行总金额（数量*单价）
     */
    @PrivateMcpToolParam(description = "行总金额（数量*单价）")
    private BigDecimal amount;

    /**
     * 销售时点成本价（用于核算毛利）
     */
    @PrivateMcpToolParam(description = "销售时点成本价（用于核算毛利）")
    private BigDecimal costPrice;
}
