package com.kevin.mcp.mapper;

import com.kevin.mcp.entity.OrgCompany;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 定义公司信息表的 MyBatis Mapper。
 *
 * @author Kevin
 * @date 2026/7/31
 */
public interface OrgCompanyMapper {
    /**
     * 新增公司信息表记录。
     *
     * @param entity 待新增的公司信息表实体
     * @return 受影响的记录条数
     */
    int insert(OrgCompany entity);

    /**
     * 根据主键查询单条公司信息表记录。
     *
     * @param id 主键ID
     * @return 匹配的公司信息表实体；不存在时返回 null
     */
    OrgCompany selectById(@Param("id") Long id);

    /**
     * 按条件查询公司信息表列表。
     *
     * @param entity 查询条件；仅使用非空字段参与过滤
     * @return 匹配的公司信息表列表
     */
    List<OrgCompany> selectByCondition(OrgCompany entity);

    /**
     * 按条件统计公司信息表数量。
     *
     * @param entity 统计条件；仅使用非空字段参与过滤
     * @return 匹配的记录数量
     */
    Long countByCondition(OrgCompany entity);

    /**
     * 根据主键更新公司信息表记录。
     *
     * @param entity 待更新实体；主键不能为空，且至少提供一个需要更新的非空字段
     * @return 受影响的记录条数
     */
    int updateById(OrgCompany entity);

    /**
     * 根据主键删除公司信息表记录。
     *
     * @param id 主键ID
     * @return 受影响的记录条数
     */
    int deleteById(@Param("id") Long id);
}
