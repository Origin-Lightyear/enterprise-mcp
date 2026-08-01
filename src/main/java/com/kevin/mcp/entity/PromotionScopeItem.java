package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * 促销商品及赠品规则表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "促销商品及赠品规则表")
public class PromotionScopeItem {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 促销规则ID
     */
    @PrivateMcpToolParam(description = "促销规则ID")
    private Long promotionId;

    /**
     * 适用商品SKU ID（主商品）
     */
    @PrivateMcpToolParam(description = "适用商品SKU ID（主商品）")
    private Long skuId;

    /**
     * 赠品SKU ID（买一赠一中的赠品，0表示无赠品）
     */
    @PrivateMcpToolParam(description = "赠品SKU ID（买一赠一中的赠品，0表示无赠品）")
    private Long giftSkuId;

    /**
     * 若为折扣类型，填写折扣率（如85.00）
     */
    @PrivateMcpToolParam(description = "若为折扣类型，填写折扣率（如85.00）")
    private BigDecimal discountRate;

    /**
     * 满足促销的最少购买数量
     */
    @PrivateMcpToolParam(description = "满足促销的最少购买数量")
    private Integer minQty;
}
