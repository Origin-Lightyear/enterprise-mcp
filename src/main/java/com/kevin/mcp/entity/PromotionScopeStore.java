package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 促销适用门店范围表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "促销适用门店范围表")
public class PromotionScopeStore {
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
     * 适用门店ID
     */
    @PrivateMcpToolParam(description = "适用门店ID")
    private Long storeId;
}
