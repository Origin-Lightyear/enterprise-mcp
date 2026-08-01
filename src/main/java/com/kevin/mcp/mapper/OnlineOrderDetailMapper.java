package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.OnlineOrderDetail;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义线上订单明细表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface OnlineOrderDetailMapper {
    /**
     * 新增线上订单明细表记录。
     *
     * @param entity 待新增的线上订单明细表实体
     * @return 受影响的记录条数
     */
    int insert(OnlineOrderDetail entity);

    /**
     * 根据主键查询单条线上订单明细表记录。
     *
     * @param id 主键ID
     * @return 匹配的线上订单明细表实体；不存在时返回 null
     */
    OnlineOrderDetail selectById(@Param("id") Long id);

    /**
     * 按条件查询线上订单明细表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的线上订单明细表列表
     */
    List<OnlineOrderDetail> selectByCondition(OnlineOrderDetail entity);

    /**
     * 按条件统计线上订单明细表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(OnlineOrderDetail entity);

    /**
     * 根据主键更新线上订单明细表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(OnlineOrderDetail entity);

    /**
     * 根据主键删除线上订单明细表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
