package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 商品分类表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "商品分类表")
public class ItemCategory {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 父分类ID（0表示一级分类）
     */
    @PrivateMcpToolParam(description = "父分类ID（0表示一级分类）")
    private Long parentId;

    /**
     * 分类名称
     */
    @PrivateMcpToolParam(description = "分类名称")
    private String name;

    /**
     * 同级排序号
     */
    @PrivateMcpToolParam(description = "同级排序号")
    private Integer sortOrder;

    /**
     * 层级深度（1/2/3）
     */
    @PrivateMcpToolParam(description = "层级深度（1/2/3）")
    private Integer level;
}
