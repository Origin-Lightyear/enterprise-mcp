package com.kevin.mcp.annotation;

/**
 * 标识能力处理多项输入时支持的批量方式。
 *
 * @author Kevin
 * @date 2026-09-17
 */
public enum BatchMode {

    /** 每次调用只处理一个业务请求。 */
    SINGLE,

    /** 能力自身支持一次处理一批输入。 */
    BATCH,

    /** 能力不支持批量接口，规划器可以使用受限 foreach。 */
    FOREACH_FALLBACK
}
