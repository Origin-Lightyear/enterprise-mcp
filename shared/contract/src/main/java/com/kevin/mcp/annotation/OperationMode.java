package com.kevin.mcp.annotation;

/**
 * 描述客户内网能力是否会修改外部资源。
 *
 * @author Kevin
 * @date 2026-09-09
 */
public enum OperationMode {

    /** 只读取数据，不产生业务写入。 */
    READ_ONLY,

    /** 修改单个本地资源，事务边界由能力内部负责。 */
    WRITE
}
