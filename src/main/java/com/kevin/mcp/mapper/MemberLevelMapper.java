package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.MemberLevel;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义会员等级配置表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface MemberLevelMapper {
    /**
     * 新增会员等级配置表记录。
     *
     * @param entity 待新增的会员等级配置表实体
     * @return 受影响的记录条数
     */
    int insert(MemberLevel entity);

    /**
     * 根据主键查询单条会员等级配置表记录。
     *
     * @param id 主键ID
     * @return 匹配的会员等级配置表实体；不存在时返回 null
     */
    MemberLevel selectById(@Param("id") Long id);

    /**
     * 按条件查询会员等级配置表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的会员等级配置表列表
     */
    List<MemberLevel> selectByCondition(MemberLevel entity);

    /**
     * 按条件统计会员等级配置表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(MemberLevel entity);

    /**
     * 根据主键更新会员等级配置表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(MemberLevel entity);

    /**
     * 根据主键删除会员等级配置表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
