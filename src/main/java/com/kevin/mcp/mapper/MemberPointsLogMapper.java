package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.MemberPointsLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义会员积分流水日志的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface MemberPointsLogMapper {
    /**
     * 新增会员积分流水日志记录。
     *
     * @param entity 待新增的会员积分流水日志实体
     * @return 受影响的记录条数
     */
    int insert(MemberPointsLog entity);

    /**
     * 根据主键查询单条会员积分流水日志记录。
     *
     * @param id 主键ID
     * @return 匹配的会员积分流水日志实体；不存在时返回 null
     */
    MemberPointsLog selectById(@Param("id") Long id);

    /**
     * 按条件查询会员积分流水日志列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的会员积分流水日志列表
     */
    List<MemberPointsLog> selectByCondition(MemberPointsLog entity);

    /**
     * 按条件统计会员积分流水日志数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(MemberPointsLog entity);

    /**
     * 根据主键更新会员积分流水日志记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(MemberPointsLog entity);

    /**
     * 根据主键删除会员积分流水日志记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
