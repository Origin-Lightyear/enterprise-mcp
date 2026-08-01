package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.Supplier;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义供应商信息表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface SupplierMapper {
    /**
     * 新增供应商信息表记录。
     *
     * @param entity 待新增的供应商信息表实体
     * @return 受影响的记录条数
     */
    int insert(Supplier entity);

    /**
     * 根据主键查询单条供应商信息表记录。
     *
     * @param id 主键ID
     * @return 匹配的供应商信息表实体；不存在时返回 null
     */
    Supplier selectById(@Param("id") Long id);

    /**
     * 按条件查询供应商信息表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的供应商信息表列表
     */
    List<Supplier> selectByCondition(Supplier entity);

    /**
     * 按条件统计供应商信息表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(Supplier entity);

    /**
     * 根据主键更新供应商信息表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(Supplier entity);

    /**
     * 根据主键删除供应商信息表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
