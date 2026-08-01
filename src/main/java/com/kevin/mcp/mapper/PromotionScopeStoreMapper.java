package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.PromotionScopeStore;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义促销适用门店范围表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface PromotionScopeStoreMapper {
    /**
     * 新增促销适用门店范围表记录。
     *
     * @param entity 待新增的促销适用门店范围表实体
     * @return 受影响的记录条数
     */
    int insert(PromotionScopeStore entity);

    /**
     * 根据主键查询单条促销适用门店范围表记录。
     *
     * @param id 主键ID
     * @return 匹配的促销适用门店范围表实体；不存在时返回 null
     */
    PromotionScopeStore selectById(@Param("id") Long id);

    /**
     * 按条件查询促销适用门店范围表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的促销适用门店范围表列表
     */
    List<PromotionScopeStore> selectByCondition(PromotionScopeStore entity);

    /**
     * 按条件统计促销适用门店范围表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(PromotionScopeStore entity);

    /**
     * 根据主键更新促销适用门店范围表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(PromotionScopeStore entity);

    /**
     * 根据主键删除促销适用门店范围表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
