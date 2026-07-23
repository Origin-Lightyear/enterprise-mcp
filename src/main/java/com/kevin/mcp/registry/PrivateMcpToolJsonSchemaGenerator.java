package com.kevin.mcp.registry;

import com.kevin.mcp.annotation.PrivateMcpToolParam;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.*;

/**
 * 生成私有 MCP 方法的结构化 Schema 描述。
 * 复用 {@link PrivateMcpToolParam} 上的业务语义，只暴露显式标注的参数、实体类与字段，避免把内部实现细节无差别泄露给外部调用方。
 */
public final class PrivateMcpToolJsonSchemaGenerator {

    private PrivateMcpToolJsonSchemaGenerator() {
    }

    /**
     * 生成方法入参 Schema。
     * 以方法参数为根对象展开，便于上层直接将其作为工具入参契约复用。
     *
     * @param method 目标方法
     * @return 方法入参对应的结构化 Schema
     */
    public static PrivateMcpToolSchemaDescriptor.MethodSchema generateMethodInputSchema(Method method) {
        List<PrivateMcpToolSchemaDescriptor.SchemaProperty> properties = new ArrayList<>();
        List<String> required = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            Parameter parameter = parameters[index];
            PrivateMcpToolParam annotation = parameter.getAnnotation(PrivateMcpToolParam.class);
            if (annotation == null) {
                continue;
            }
            String parameterName = resolveParameterName(parameter, index);
            PrivateMcpToolSchemaDescriptor.MethodSchema parameterSchema =
                    buildSchema(parameter.getParameterizedType(), annotation.description(), new HashSet<>());
            properties.add(new PrivateMcpToolSchemaDescriptor.SchemaProperty(parameterName, parameterSchema));
            if (annotation.required()) {
                required.add(parameterName);
            }
        }

        return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                "object",
                "",
                Boolean.FALSE,
                required,
                properties,
                null,
                List.of()
        );
    }

    /**
     * 生成方法返回值 Schema。
     * 返回复杂对象时会优先读取实体类上的 {@link PrivateMcpToolParam} 描述，避免顶层输出节点缺少业务语义。
     *
     * @param method 目标方法
     * @return 方法返回值对应的结构化 Schema
     */
    public static PrivateMcpToolSchemaDescriptor.MethodSchema generateMethodOutputSchema(Method method) {
        Type returnType = method.getGenericReturnType();
        if (Void.TYPE.equals(returnType) || Void.class.equals(returnType)) {
            return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                    "null",
                    "",
                    null,
                    List.of(),
                    List.of(),
                    null,
                    List.of()
            );
        }
        return buildSchema(returnType, "", new HashSet<>());
    }

    /**
     * 生成实体对象对应的 Schema。
     * 供通用序列化工具在输出业务数据时按需附带结构定义，避免每处都自行拼装 Schema 元数据。
     *
     * @param source 实体对象
     * @return 对应的结构化 Schema
     */
    public static PrivateMcpToolSchemaDescriptor.MethodSchema generateObjectSchema(Object source) {
        if (source == null) {
            return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                    "null",
                    "",
                    null,
                    List.of(),
                    List.of(),
                    null,
                    List.of()
            );
        }
        return generateClassSchema(source.getClass());
    }

    /**
     * 生成指定类对应的 Schema。
     * 让调用方在尚未构造实体实例时，也能直接复用统一的类级 Schema 生成逻辑。
     *
     * @param type 实体类
     * @return 对应的结构化 Schema
     */
    public static PrivateMcpToolSchemaDescriptor.MethodSchema generateClassSchema(Class<?> type) {
        return buildSchema(type, "", new HashSet<>());
    }

    /**
     * 构建任意 Java 类型对应的 Schema 节点。
     * 通过 visited 集合打断循环引用，保证递归展开复杂对象时不会因为双向关联导致启动失败。
     *
     * @param type 目标类型
     * @param description 描述信息
     * @param visited 当前递归路径上的类型集合
     * @return 结构化 Schema 节点
     */
    private static PrivateMcpToolSchemaDescriptor.MethodSchema buildSchema(Type type, String description, Set<Type> visited) {
        Type normalizedType = unwrapOptionalType(type);
        Class<?> rawClass = resolveRawClass(normalizedType);
        String normalizedDescription = resolveSchemaDescription(rawClass, description);
        if (visited.contains(normalizedType)) {
            return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                    "object",
                    normalizedDescription,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    List.of()
            );
        }

        if (rawClass == null) {
            return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                    "object",
                    normalizedDescription,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    List.of()
            );
        }

        if (CharSequence.class.isAssignableFrom(rawClass) || Character.class.equals(rawClass) || char.class.equals(rawClass)
                || UUID.class.equals(rawClass) || Date.class.isAssignableFrom(rawClass) || Temporal.class.isAssignableFrom(rawClass)) {
            return primitiveSchema("string", normalizedDescription);
        }
        if (Boolean.class.equals(rawClass) || boolean.class.equals(rawClass)) {
            return primitiveSchema("boolean", normalizedDescription);
        }
        if (isIntegerType(rawClass)) {
            return primitiveSchema("integer", normalizedDescription);
        }
        if (isNumberType(rawClass)) {
            return primitiveSchema("number", normalizedDescription);
        }
        if (rawClass.isEnum()) {
            List<String> enumValues = new ArrayList<>();
            for (Object enumConstant : rawClass.getEnumConstants()) {
                enumValues.add(String.valueOf(enumConstant));
            }
            return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                    "string",
                    normalizedDescription,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    enumValues
            );
        }
        if (rawClass.isArray()) {
            return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                    "array",
                    normalizedDescription,
                    null,
                    List.of(),
                    List.of(),
                    buildSchema(rawClass.getComponentType(), "", new HashSet<>(visited)),
                    List.of()
            );
        }
        if (Collection.class.isAssignableFrom(rawClass)) {
            return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                    "array",
                    normalizedDescription,
                    null,
                    List.of(),
                    List.of(),
                    buildSchema(resolveCollectionItemType(normalizedType), "", new HashSet<>(visited)),
                    List.of()
            );
        }
        if (Map.class.isAssignableFrom(rawClass)) {
            return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                    "object",
                    normalizedDescription,
                    Boolean.TRUE,
                    List.of(),
                    List.of(),
                    null,
                    List.of()
            );
        }

        visited.add(normalizedType);
        List<PrivateMcpToolSchemaDescriptor.SchemaProperty> properties = new ArrayList<>();
        List<String> required = new ArrayList<>();
        for (Field field : getAllFields(rawClass)) {
            PrivateMcpToolParam annotation = field.getAnnotation(PrivateMcpToolParam.class);
            if (annotation == null) {
                continue;
            }
            PrivateMcpToolSchemaDescriptor.MethodSchema fieldSchema =
                    buildSchema(field.getGenericType(), annotation.description(), new HashSet<>(visited));
            properties.add(new PrivateMcpToolSchemaDescriptor.SchemaProperty(field.getName(), fieldSchema));
            if (annotation.required()) {
                required.add(field.getName());
            }
        }
        return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                "object",
                normalizedDescription,
                Boolean.FALSE,
                required,
                properties,
                null,
                List.of()
        );
    }

    /**
     * 解析 Schema 节点描述。
     * 当调用方没有显式传描述时，回退读取实体类上的 {@link PrivateMcpToolParam}，让返回实体类的顶层节点保留业务语义。
     *
     * @param rawClass 当前节点的原始类型
     * @param description 显式传入的描述
     * @return 最终描述
     */
    private static String resolveSchemaDescription(Class<?> rawClass, String description) {
        if (description != null && !description.isBlank()) {
            return description;
        }
        if (rawClass == null) {
            return "";
        }
        PrivateMcpToolParam annotation = rawClass.getAnnotation(PrivateMcpToolParam.class);
        if (annotation == null || annotation.description().isBlank()) {
            return "";
        }
        return annotation.description();
    }

    /**
     * 构建基础类型节点。
     * 将无子结构的简单节点集中构造，避免主流程重复拼装空集合。
     *
     * @param type JSON Schema 类型
     * @param description 节点描述
     * @return 简单节点定义
     */
    private static PrivateMcpToolSchemaDescriptor.MethodSchema primitiveSchema(String type, String description) {
        return new PrivateMcpToolSchemaDescriptor.MethodSchema(
                type,
                description,
                null,
                List.of(),
                List.of(),
                null,
                List.of()
        );
    }

    /**
     * 解析参数名。
     * 在未开启 -parameters 编译参数时退化为 argN，确保注册阶段仍能稳定产出可消费的 Schema。
     *
     * @param parameter 方法参数
     * @param index 参数下标
     * @return 可用于 Schema 的字段名
     */
    private static String resolveParameterName(Parameter parameter, int index) {
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        return "arg" + index;
    }

    /**
     * 提取 Optional 包裹的真实类型。
     * 避免把 Optional 本身错误描述成对象结构，影响模型理解真实入参契约。
     *
     * @param type 原始类型
     * @return 去壳后的类型
     */
    private static Type unwrapOptionalType(Type type) {
        if (type instanceof ParameterizedType parameterizedType && Optional.class.equals(parameterizedType.getRawType())) {
            return parameterizedType.getActualTypeArguments()[0];
        }
        return type;
    }

    /**
     * 解析集合元素类型。
     * 集合缺失泛型信息时保守退化为 Object，避免在运行期做不可靠的类型猜测。
     *
     * @param type 集合类型
     * @return 元素类型
     */
    private static Type resolveCollectionItemType(Type type) {
        if (type instanceof ParameterizedType parameterizedType) {
            return parameterizedType.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    /**
     * 解析 Type 对应的原始 Class。
     * 统一处理 Class、ParameterizedType 和 GenericArrayType，减少递归构建时的类型分支复杂度。
     *
     * @param type 反射类型
     * @return 原始 Class，无法解析时返回 {@code null}
     */
    private static Class<?> resolveRawClass(Type type) {
        if (type instanceof Class<?> rawClass) {
            return rawClass;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();
            if (rawType instanceof Class<?> rawClass) {
                return rawClass;
            }
        }
        if (type instanceof GenericArrayType genericArrayType) {
            Class<?> componentType = resolveRawClass(genericArrayType.getGenericComponentType());
            if (componentType != null) {
                return componentType.arrayType();
            }
        }
        return null;
    }

    /**
     * 收集类及父类上的全部字段。
     * 允许返回体继承公共基类时仍能识别父类中声明的业务字段注解。
     *
     * @param type 目标类型
     * @return 按继承层级展开的字段列表
     */
    private static List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && !Object.class.equals(current)) {
            Field[] declaredFields = current.getDeclaredFields();
            Collections.addAll(fields, declaredFields);
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * 判断是否为整数类型。
     * 将 Schema 粒度控制在 integer 和 number 两类，便于与主流 tool 调用协议保持一致。
     *
     * @param rawClass 原始类型
     * @return 是否为整数类型
     */
    private static boolean isIntegerType(Class<?> rawClass) {
        return byte.class.equals(rawClass)
                || short.class.equals(rawClass)
                || int.class.equals(rawClass)
                || long.class.equals(rawClass)
                || Byte.class.equals(rawClass)
                || Short.class.equals(rawClass)
                || Integer.class.equals(rawClass)
                || Long.class.equals(rawClass)
                || BigInteger.class.equals(rawClass);
    }

    /**
     * 判断是否为浮点数字类型。
     * BigDecimal 也归入 number，方便模型根据描述生成带小数的业务参数。
     *
     * @param rawClass 原始类型
     * @return 是否为 number 类型
     */
    private static boolean isNumberType(Class<?> rawClass) {
        return float.class.equals(rawClass)
                || double.class.equals(rawClass)
                || Float.class.equals(rawClass)
                || Double.class.equals(rawClass)
                || BigDecimal.class.equals(rawClass)
                || Number.class.equals(rawClass);
    }
}
