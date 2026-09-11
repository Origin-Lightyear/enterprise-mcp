package com.kevin.mcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明跨云端规划与客户内网执行之间稳定的能力契约。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LocalCapability {

    /** 返回跨版本保持稳定的能力编号。 */
    String id();

    /** 返回能力契约版本。 */
    int version() default 1;

    /** 返回能力的数据访问模式。 */
    OperationMode mode() default OperationMode.READ_ONLY;

    /** 返回能力所属的数据域。 */
    String dataDomain() default "";
}
