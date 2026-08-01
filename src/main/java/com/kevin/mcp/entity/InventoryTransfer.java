package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 库存调拨单
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "库存调拨单")
public class InventoryTransfer {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 调拨单号
     */
    @PrivateMcpToolParam(description = "调拨单号")
    private String transferNo;

    /**
     * 调出仓库ID（0表示总仓，非0表示门店互调）
     */
    @PrivateMcpToolParam(description = "调出仓库ID（0表示总仓，非0表示门店互调）")
    private Long fromWarehouseId;

    /**
     * 目标门店ID
     */
    @PrivateMcpToolParam(description = "目标门店ID")
    private Long toStoreId;

    /**
     * 商品SKU ID
     */
    @PrivateMcpToolParam(description = "商品SKU ID")
    private Long skuId;

    /**
     * 调拨数量
     */
    @PrivateMcpToolParam(description = "调拨数量")
    private Integer qty;

    /**
     * 状态：0待出库，1配送中，2已收货，3拒收/退货
     */
    @PrivateMcpToolParam(description = "状态：0待出库，1配送中，2已收货，3拒收/退货")
    private Integer status;

    /**
     * 出库时间
     */
    @PrivateMcpToolParam(description = "出库时间")
    private LocalDateTime outTime;

    /**
     * 门店确认收货时间
     */
    @PrivateMcpToolParam(description = "门店确认收货时间")
    private LocalDateTime receiveTime;

    /**
     * 制单人ID
     */
    @PrivateMcpToolParam(description = "制单人ID")
    private Long createdBy;

    /**
     * 创建时间
     */
    @PrivateMcpToolParam(description = "创建时间")
    private LocalDateTime createdAt;
}
