package com.kevin.mcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要复用 Runtime 身份、权限和审计链路的公开探索工具。
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicMcpTool {

    /** 返回公开工具的稳定编号。 */
    String capabilityId();

    /** 返回交给员工权限审查模型的业务能力描述。 */
    String description();
}
