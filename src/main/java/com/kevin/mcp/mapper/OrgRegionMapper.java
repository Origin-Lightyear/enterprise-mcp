package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.OrgRegion;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义区域组织机构表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface OrgRegionMapper {
    /**
     * 新增区域组织机构表记录。
     *
     * @param entity 待新增的区域组织机构表实体
     * @return 受影响的记录条数
     */
    int insert(OrgRegion entity);

    /**
     * 根据主键查询单条区域组织机构表记录。
     *
     * @param id 主键ID
     * @return 匹配的区域组织机构表实体；不存在时返回 null
     */
    OrgRegion selectById(@Param("id") Long id);

    /**
     * 按条件查询区域组织机构表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的区域组织机构表列表
     */
    List<OrgRegion> selectByCondition(OrgRegion entity);

    /**
     * 按条件统计区域组织机构表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(OrgRegion entity);

    /**
     * 根据主键更新区域组织机构表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(OrgRegion entity);

    /**
     * 根据主键删除区域组织机构表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
