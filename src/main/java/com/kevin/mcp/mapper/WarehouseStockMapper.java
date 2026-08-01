package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.WarehouseStock;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义总仓库存实时表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface WarehouseStockMapper {
    /**
     * 新增总仓库存实时表记录。
     *
     * @param entity 待新增的总仓库存实时表实体
     * @return 受影响的记录条数
     */
    int insert(WarehouseStock entity);

    /**
     * 根据主键查询单条总仓库存实时表记录。
     *
     * @param id 主键ID
     * @return 匹配的总仓库存实时表实体；不存在时返回 null
     */
    WarehouseStock selectById(@Param("id") Long id);

    /**
     * 按条件查询总仓库存实时表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的总仓库存实时表列表
     */
    List<WarehouseStock> selectByCondition(WarehouseStock entity);

    /**
     * 按条件统计总仓库存实时表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(WarehouseStock entity);

    /**
     * 根据主键更新总仓库存实时表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(WarehouseStock entity);

    /**
     * 根据主键删除总仓库存实时表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
