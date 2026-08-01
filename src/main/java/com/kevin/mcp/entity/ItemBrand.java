package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 商品品牌表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "商品品牌表")
public class ItemBrand {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 品牌名称
     */
    @PrivateMcpToolParam(description = "品牌名称")
    private String name;

    /**
     * 品牌LOGO图片地址
     */
    @PrivateMcpToolParam(description = "品牌LOGO图片地址")
    private String logoUrl;

    /**
     * 品牌首字母（用于快速检索）
     */
    @PrivateMcpToolParam(description = "品牌首字母（用于快速检索）")
    private String firstLetter;

    /**
     * 状态：1启用，0停用
     */
    @PrivateMcpToolParam(description = "状态：1启用，0停用")
    private Integer status;
}
