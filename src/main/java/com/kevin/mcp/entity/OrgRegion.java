package com.kevin.mcp.entity;

import com.kevin.mcp.annotation.PrivateMcpToolParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 区域组织机构表
 *
 * @author Kevin
 * @date 2026/7/31
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@PrivateMcpToolParam(description = "区域组织机构表")
public class OrgRegion {
    /**
     * 主键ID
     */
    @PrivateMcpToolParam(description = "主键ID")
    private Long id;

    /**
     * 所属公司ID
     */
    @PrivateMcpToolParam(description = "所属公司ID")
    private Long companyId;

    /**
     * 父级区域ID（0表示根节点）
     */
    @PrivateMcpToolParam(description = "父级区域ID（0表示根节点）")
    private Long parentId;

    /**
     * 区域名称（如：华北区/北京市/朝阳区）
     */
    @PrivateMcpToolParam(description = "区域名称（如：华北区/北京市/朝阳区）")
    private String name;

    /**
     * 层级：1大区，2城市，3商圈/区县
     */
    @PrivateMcpToolParam(description = "层级：1大区，2城市，3商圈/区县")
    private Integer level;

    /**
     * 排序号
     */
    @PrivateMcpToolParam(description = "排序号")
    private Integer sortOrder;

    /**
     * 状态：1启用，0停用
     */
    @PrivateMcpToolParam(description = "状态：1启用，0停用")
    private Integer status;
}
