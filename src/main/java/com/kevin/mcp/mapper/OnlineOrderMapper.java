package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.OnlineOrder;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义线上第三方订单主表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface OnlineOrderMapper {
    /**
     * 新增线上第三方订单主表记录。
     *
     * @param entity 待新增的线上第三方订单主表实体
     * @return 受影响的记录条数
     */
    int insert(OnlineOrder entity);

    /**
     * 根据主键查询单条线上第三方订单主表记录。
     *
     * @param id 主键ID
     * @return 匹配的线上第三方订单主表实体；不存在时返回 null
     */
    OnlineOrder selectById(@Param("id") Long id);

    /**
     * 按条件查询线上第三方订单主表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的线上第三方订单主表列表
     */
    List<OnlineOrder> selectByCondition(OnlineOrder entity);

    /**
     * 按条件统计线上第三方订单主表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(OnlineOrder entity);

    /**
     * 根据主键更新线上第三方订单主表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(OnlineOrder entity);

    /**
     * 根据主键删除线上第三方订单主表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
