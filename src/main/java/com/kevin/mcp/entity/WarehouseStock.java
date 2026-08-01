package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 总仓库存实时表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "总仓库存实时表")
public class WarehouseStock {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 仓库ID（1表示总仓）
     */
    @PrivateMcpToolParam(description = "仓库ID（1表示总仓）")
    private Long warehouseId;

    /**
     * 商品SKU ID
     */
    @PrivateMcpToolParam(description = "商品SKU ID")
    private Long skuId;

    /**
     * 现有实物库存数量
     */
    @PrivateMcpToolParam(description = "现有实物库存数量")
    private Integer qty;

    /**
     * 锁定库存（已分配但未出库，如配货中）
     */
    @PrivateMcpToolParam(description = "锁定库存（已分配但未出库，如配货中）")
    private Integer lockQty;

    /**
     * 可用库存（虚拟计算列）
     */
    @PrivateMcpToolParam(description = "可用库存（虚拟计算列）")
    private Integer availableQty;

    /**
     * 最后更新时间
     */
    @PrivateMcpToolParam(description = "最后更新时间")
    private LocalDateTime updatedAt;
}
