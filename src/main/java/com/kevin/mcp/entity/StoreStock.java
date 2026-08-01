package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 门店库存实时表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "门店库存实时表")
public class StoreStock {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 门店ID
     */
    @PrivateMcpToolParam(description = "门店ID")
    private Long storeId;

    /**
     * 商品SKU ID
     */
    @PrivateMcpToolParam(description = "商品SKU ID")
    private Long skuId;

    /**
     * 门店实物库存（含残次/预留）
     */
    @PrivateMcpToolParam(description = "门店实物库存（含残次/预留）")
    private Integer qty;

    /**
     * 锁定库存（线上订单待拣货占用）
     */
    @PrivateMcpToolParam(description = "锁定库存（线上订单待拣货占用）")
    private Integer lockQty;

    /**
     * 门店可售库存（虚拟列）
     */
    @PrivateMcpToolParam(description = "门店可售库存（虚拟列）")
    private Integer availableQty;

    /**
     * 最后盘点/更新时间
     */
    @PrivateMcpToolParam(description = "最后盘点/更新时间")
    private LocalDateTime updatedAt;
}
