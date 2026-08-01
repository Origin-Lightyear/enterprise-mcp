package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.WarehouseReceipt;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义总仓入库单（库存流水）的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface WarehouseReceiptMapper {
    /**
     * 新增总仓入库单（库存流水）记录。
     *
     * @param entity 待新增的总仓入库单（库存流水）实体
     * @return 受影响的记录条数
     */
    int insert(WarehouseReceipt entity);

    /**
     * 根据主键查询单条总仓入库单（库存流水）记录。
     *
     * @param id 主键ID
     * @return 匹配的总仓入库单（库存流水）实体；不存在时返回 null
     */
    WarehouseReceipt selectById(@Param("id") Long id);

    /**
     * 按条件查询总仓入库单（库存流水）列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的总仓入库单（库存流水）列表
     */
    List<WarehouseReceipt> selectByCondition(WarehouseReceipt entity);

    /**
     * 按条件统计总仓入库单（库存流水）数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(WarehouseReceipt entity);

    /**
     * 根据主键更新总仓入库单（库存流水）记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(WarehouseReceipt entity);

    /**
     * 根据主键删除总仓入库单（库存流水）记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
