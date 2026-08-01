package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SKU商品主数据表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "SKU商品主数据表")
public class ItemSku {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 商品条码/内部自编码
     */
    @PrivateMcpToolParam(description = "商品条码/内部自编码")
    private String code;

    /**
     * 商品名称
     */
    @PrivateMcpToolParam(description = "商品名称")
    private String name;

    /**
     * 所属分类ID
     */
    @PrivateMcpToolParam(description = "所属分类ID")
    private Long categoryId;

    /**
     * 所属品牌ID
     */
    @PrivateMcpToolParam(description = "所属品牌ID")
    private Long brandId;

    /**
     * 规格参数（如：500ml， L码）
     */
    @PrivateMcpToolParam(description = "规格参数（如：500ml， L码）")
    private String spec;

    /**
     * 销售单位（个/箱/袋/瓶）
     */
    @PrivateMcpToolParam(description = "销售单位（个/箱/袋/瓶）")
    private String unit;

    /**
     * 最新采购成本价（含税）
     */
    @PrivateMcpToolParam(description = "最新采购成本价（含税）")
    private BigDecimal costPrice;

    /**
     * 标准零售价（前台价签）
     */
    @PrivateMcpToolParam(description = "标准零售价（前台价签）")
    private BigDecimal retailPrice;

    /**
     * 默认会员价（可被会员等级折扣覆盖）
     */
    @PrivateMcpToolParam(description = "默认会员价（可被会员等级折扣覆盖）")
    private BigDecimal vipPrice;

    /**
     * 商品毛重（千克）
     */
    @PrivateMcpToolParam(description = "商品毛重（千克）")
    private BigDecimal weightKg;

    /**
     * 状态：1上架，0下架
     */
    @PrivateMcpToolParam(description = "状态：1上架，0下架")
    private Integer status;

    /**
     * 创建时间
     */
    @PrivateMcpToolParam(description = "创建时间")
    private LocalDateTime createdAt;
}
