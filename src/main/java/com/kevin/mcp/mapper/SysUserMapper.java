package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.SysUser;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义系统用户表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface SysUserMapper {
    /**
     * 新增系统用户表记录。
     *
     * @param entity 待新增的系统用户表实体
     * @return 受影响的记录条数
     */
    int insert(SysUser entity);

    /**
     * 根据主键查询单条系统用户表记录。
     *
     * @param id 主键ID
     * @return 匹配的系统用户表实体；不存在时返回 null
     */
    SysUser selectById(@Param("id") Long id);

    /**
     * 按条件查询系统用户表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的系统用户表列表
     */
    List<SysUser> selectByCondition(SysUser entity);

    /**
     * 按条件统计系统用户表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(SysUser entity);

    /**
     * 根据主键更新系统用户表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(SysUser entity);

    /**
     * 根据主键删除系统用户表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
