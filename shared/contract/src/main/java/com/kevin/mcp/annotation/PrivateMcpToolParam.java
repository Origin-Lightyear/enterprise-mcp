package com.kevin.mcp.annotation;

import java.lang.annotation.*;

/**
 * 标记内部私有 MCP Tool 的参数、实体类和字段。
 * 用于为方法入参、返回对象及其字段补充可被 Schema 注册中心消费的描述信息和必填约束。
 *
 * @author Kevin
 * 2026/7/20
 */
@Target({ ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PrivateMcpToolParam {
    /** 标记当前参数或字段是否必填，帮助模型在生成调用参数时遵守最小契约。 */
    boolean required() default true;

    /** 描述当前参数、实体类或字段的业务含义，优先服务于 Schema 和提示词生成。 */
    String description() default "";
}
