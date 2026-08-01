package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 总仓入库单（库存流水）
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "总仓入库单（库存流水）")
public class WarehouseReceipt {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 入库单号
     */
    @PrivateMcpToolParam(description = "入库单号")
    private String receiptNo;

    /**
     * 关联采购订单ID（0表示非采购入库）
     */
    @PrivateMcpToolParam(description = "关联采购订单ID（0表示非采购入库）")
    private Long poId;

    /**
     * 入库仓库ID
     */
    @PrivateMcpToolParam(description = "入库仓库ID")
    private Long warehouseId;

    /**
     * 商品SKU ID
     */
    @PrivateMcpToolParam(description = "商品SKU ID")
    private Long skuId;

    /**
     * 实际入库数量
     */
    @PrivateMcpToolParam(description = "实际入库数量")
    private Integer qty;

    /**
     * 入库成本价（最终结算价）
     */
    @PrivateMcpToolParam(description = "入库成本价（最终结算价）")
    private BigDecimal costPrice;

    /**
     * 入库日期时间
     */
    @PrivateMcpToolParam(description = "入库日期时间")
    private LocalDateTime receiptDate;

    /**
     * 质检员/验收人
     */
    @PrivateMcpToolParam(description = "质检员/验收人")
    private String inspector;
}
