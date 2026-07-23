package com.kevin.mcp.registry;

import java.util.List;

/**
 * 承载私有 MCP 方法的注册描述。
 * 用于在启动阶段完成反射注册后，以稳定结构向其他 Bean 暴露方法定位信息和输入输出 Schema。
 *
 * @param methodKey 基于类名、方法名和参数类型拼出的内部唯一键
 * @param beanName Spring Bean 名称
 * @param toolName 对外展示的工具名称，默认复用方法名
 * @param declaringClassName 声明方法的类名
 * @param methodName 方法名
 * @param parameterTypeNames 方法参数类型全限定名列表
 * @param description 工具描述
 * @param inputSchema 方法入参 Schema
 * @param outputSchema 方法出参 Schema
 */
public record PrivateMcpToolSchemaDescriptor(
        String methodKey,
        String beanName,
        String toolName,
        String declaringClassName,
        String methodName,
        List<String> parameterTypeNames,
        String description,
        PrivateMcpToolSchemaDescriptor.MethodSchema inputSchema,
        PrivateMcpToolSchemaDescriptor.MethodSchema outputSchema
) {

    /**
     * 固化参数类型列表。
     * 避免调用方修改注册快照中的集合内容，导致方法唯一键与方法签名失配。
     *
     * @param methodKey 内部唯一键
     * @param beanName Bean 名称
     * @param toolName 展示名称
     * @param declaringClassName 声明类名
     * @param methodName 方法名
     * @param parameterTypeNames 参数类型列表
     * @param description 描述
     * @param inputSchema 入参 Schema
     * @param outputSchema 出参 Schema
     */
    public PrivateMcpToolSchemaDescriptor {
        parameterTypeNames = parameterTypeNames == null ? List.of() : List.copyOf(parameterTypeNames);
    }

    /**
     * 描述单个 Schema 节点。
     * 统一覆盖对象、数组、枚举、基础类型等常见结构，便于上层直接按字段遍历或序列化为规范 JSON。
     *
     * @param type JSON Schema 类型，例如 object、array、string、integer、number、boolean、null
     * @param description 节点描述
     * @param additionalProperties 对 object 节点是否允许额外字段
     * @param required 当前 object 节点下的必填字段名列表
     * @param properties 当前 object 节点下的属性定义列表
     * @param items 当前 array 节点的元素定义
     * @param enumValues 当前 enum 节点允许的值列表
     */
    public record MethodSchema(
            String type,
            String description,
            Boolean additionalProperties,
            List<String> required,
            List<SchemaProperty> properties,
            MethodSchema items,
            List<String> enumValues
    ) {

        /**
         * 归一化可选集合字段。
         * 避免调用方到处判空，让遍历属性、必填项和枚举值时保持一致的数据访问方式。
         *
         * @param type JSON Schema 类型
         * @param description 节点描述
         * @param additionalProperties object 节点额外字段开关
         * @param required object 节点必填字段列表
         * @param properties object 节点属性定义
         * @param items array 节点元素定义
         * @param enumValues enum 节点允许值
         */
        public MethodSchema {
            required = required == null ? List.of() : List.copyOf(required);
            properties = properties == null ? List.of() : List.copyOf(properties);
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }

    /**
     * 描述对象节点中的单个属性。
     * 将属性名与属性自身 Schema 绑定，便于前端展示、提示词拼接和调试输出时保持固定结构。
     *
     * @param name 属性名
     * @param schema 属性对应的 Schema 定义
     */
    public record SchemaProperty(
            String name,
            MethodSchema schema
    ) {
    }
}
