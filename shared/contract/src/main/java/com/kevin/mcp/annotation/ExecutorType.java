package com.kevin.mcp.annotation;

/**
 * 标识本地能力的实际执行器类型。
 *
 * @author Kevin
 * @date 2026-09-17
 */
public enum ExecutorType {

    /** 由客户 Java 能力方法直接执行。 */
    JAVA,

    /** 由客户本地 Wren 语义引擎生成并执行只读 SQL。 */
    WREN
}
