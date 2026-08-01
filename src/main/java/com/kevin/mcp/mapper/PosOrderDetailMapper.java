package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.PosOrderDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义POS销售订单明细表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface PosOrderDetailMapper {
    /**
     * 新增POS销售订单明细表记录。
     *
     * @param entity 待新增的POS销售订单明细表实体
     * @return 受影响的记录条数
     */
    int insert(PosOrderDetail entity);

    /**
     * 根据主键查询单条POS销售订单明细表记录。
     *
     * @param id 主键ID
     * @return 匹配的POS销售订单明细表实体；不存在时返回 null
     */
    PosOrderDetail selectById(@Param("id") Long id);

    /**
     * 按条件查询POS销售订单明细表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的POS销售订单明细表列表
     */
    List<PosOrderDetail> selectByCondition(PosOrderDetail entity);

    /**
     * 按条件统计POS销售订单明细表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(PosOrderDetail entity);

    /**
     * 根据主键更新POS销售订单明细表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(PosOrderDetail entity);

    /**
     * 根据主键删除POS销售订单明细表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
