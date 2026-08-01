package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.InventoryTransfer;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义库存调拨单的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface InventoryTransferMapper {
    /**
     * 新增库存调拨单记录。
     *
     * @param entity 待新增的库存调拨单实体
     * @return 受影响的记录条数
     */
    int insert(InventoryTransfer entity);

    /**
     * 根据主键查询单条库存调拨单记录。
     *
     * @param id 主键ID
     * @return 匹配的库存调拨单实体；不存在时返回 null
     */
    InventoryTransfer selectById(@Param("id") Long id);

    /**
     * 按条件查询库存调拨单列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的库存调拨单列表
     */
    List<InventoryTransfer> selectByCondition(InventoryTransfer entity);

    /**
     * 按条件统计库存调拨单数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(InventoryTransfer entity);

    /**
     * 根据主键更新库存调拨单记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(InventoryTransfer entity);

    /**
     * 根据主键删除库存调拨单记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
