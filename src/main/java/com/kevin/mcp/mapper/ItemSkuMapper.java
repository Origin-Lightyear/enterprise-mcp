package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.ItemSku;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义SKU商品主数据表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface ItemSkuMapper {
    /**
     * 新增SKU商品主数据表记录。
     *
     * @param entity 待新增的SKU商品主数据表实体
     * @return 受影响的记录条数
     */
    int insert(ItemSku entity);

    /**
     * 根据主键查询单条SKU商品主数据表记录。
     *
     * @param id 主键ID
     * @return 匹配的SKU商品主数据表实体；不存在时返回 null
     */
    ItemSku selectById(@Param("id") Long id);

    /**
     * 按条件查询SKU商品主数据表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的SKU商品主数据表列表
     */
    List<ItemSku> selectByCondition(ItemSku entity);

    /**
     * 按条件统计SKU商品主数据表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(ItemSku entity);

    /**
     * 根据主键更新SKU商品主数据表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(ItemSku entity);

    /**
     * 根据主键删除SKU商品主数据表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
