package com.kevin.mcp.annotation;

import java.lang.annotation.*;

/**
 * 标记内部私有 MCP Tool 方法。
 * 用于在启动阶段扫描不对外公开的方法，并基于方法签名注册可供内部模型与反射调用复用的元数据。
 *
 * @author Kevin
 * 2026/7/20
 */
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PrivateMcpTool {
    /** 描述方法的业务用途，便于生成提示词与调试信息。 */
    String description() default "";

    /** 控制是否为返回值额外生成输出 Schema，避免不需要的场景固化冗余契约。 */
    boolean generateOutputSchema() default true;
}
