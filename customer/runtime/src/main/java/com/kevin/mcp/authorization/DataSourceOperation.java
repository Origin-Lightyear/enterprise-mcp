package com.kevin.mcp.authorization;

/**
 * 定义数据库数据源可由 Tenant 授权的确定性操作类型。
 *
 * @author Kevin
 * @date 2026-10-26
 */
public enum DataSourceOperation {

    /** 查询数据库数据。 */
    QUERY,

    /** 新增数据库数据。 */
    CREATE,

    /** 修改数据库数据。 */
    UPDATE,

    /** 删除数据库数据。 */
    DELETE
}
