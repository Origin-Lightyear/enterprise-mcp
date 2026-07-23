package com.kevin.mcp.util;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.annotation.PrivateMcpToolParam;
import com.kevin.mcp.registry.PrivateMcpToolJsonSchemaGenerator;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gson 工具类。
 * 提供 JSON 序列化、反序列化以及附带私有 MCP Schema 的统一输出入口，避免业务层重复拼装通用 JSON 结构。
 *
 * @author YourName
 * @version 1.0
 */
public final class GsonUtil {

    /**
     * 默认 Gson 实例。
     * 保持线程安全的单例配置，统一处理日期格式、HTML 转义和复杂 Map Key 序列化。
     */
    private static final Gson GSON = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .disableHtmlEscaping()
            .enableComplexMapKeySerialization()
            .create();

    /**
     * 允许序列化 null 的 Gson 实例。
     * 在需要保留空字段语义时与默认实例分离，避免普通输出引入冗余 null 字段。
     */
    private static final Gson GSON_NULL = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .disableHtmlEscaping()
            .enableComplexMapKeySerialization()
            .serializeNulls()
            .create();

    private GsonUtil() {
        // 工具类禁止实例化
    }

    // ==================== 序列化 ====================

    /**
     * 将对象转为 JSON 字符串。
     * 默认不序列化 null 字段，适合绝大多数常规接口和日志输出场景。
     *
     * @param source 待序列化对象
     * @return JSON 字符串
     */
    public static String toJson(Object source) {
        return GSON.toJson(source);
    }

    /**
     * 将对象转为带 Schema 的 JSON 字符串。
     * 当对象类型带有 {@link PrivateMcpToolParam} 类级注解时，除了原始数据外还会附带一份结构化 Schema，便于模型或下游系统同时理解数据内容与字段契约。
     *
     * @param source 待序列化对象
     * @return 普通 JSON，或包含 data 和 schema 的包装 JSON
     */
    public static String toJsonWithSchema(Object source) {
        if (source == null) {
            return toJson(null);
        }
        Class<?> sourceClass = source.getClass();
        if (!sourceClass.isAnnotationPresent(PrivateMcpToolParam.class)) {
            return toJson(source);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", source);
        payload.put("schema", PrivateMcpToolJsonSchemaGenerator.generateObjectSchema(source));
        return toJson(payload);
    }

    /**
     * 将对象转为 JSON 字符串。
     * 该方法会保留 null 字段，适合需要显式表达“字段存在但当前为空”的场景。
     *
     * @param source 待序列化对象
     * @return JSON 字符串
     */
    public static String toJsonWithNulls(Object source) {
        return GSON_NULL.toJson(source);
    }

    /**
     * 将对象转为格式化后的 JSON 字符串。
     * 主要用于调试和人工阅读，不序列化 null 字段以减少噪音。
     *
     * @param source 待序列化对象
     * @return 格式化 JSON 字符串
     */
    public static String toPrettyJson(Object source) {
        return GSON.newBuilder().setPrettyPrinting().create().toJson(source);
    }

    /**
     * 将对象转为格式化后的 JSON 字符串。
     * 在保留 null 字段的同时增强可读性，适合问题排查或比对完整载荷。
     *
     * @param source 待序列化对象
     * @return 格式化 JSON 字符串
     */
    public static String toPrettyJsonWithNulls(Object source) {
        return GSON_NULL.newBuilder().setPrettyPrinting().create().toJson(source);
    }

    // ==================== 反序列化（基础） ====================

    /**
     * 将 JSON 字符串反序列化为指定类型对象。
     *
     * @param json JSON 字符串
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 反序列化后的对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    /**
     * 将 JSON 字符串反序列化为指定 Type 对象。
     * 适用于需要保留泛型参数的场景，例如 Map<String, Object> 或 List<Order>。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T> 泛型类型
     * @return 反序列化后的对象
     */
    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    /**
     * 将 JSON 字符串反序列化为指定 TypeToken 对应的类型。
     *
     * @param json JSON 字符串
     * @param token 类型令牌
     * @param <T> 泛型类型
     * @return 反序列化后的对象
     */
    public static <T> T fromJson(String json, TypeToken<T> token) {
        return GSON.fromJson(json, token.getType());
    }

    // ==================== 反序列化（集合） ====================

    /**
     * 将 JSON 数组字符串反序列化为 List。
     *
     * @param json JSON 数组字符串
     * @param itemClass 集合元素类型
     * @param <T> 集合元素泛型
     * @return List 结果
     */
    public static <T> List<T> fromJsonToList(String json, Class<T> itemClass) {
        Type type = TypeToken.getParameterized(List.class, itemClass).getType();
        return GSON.fromJson(json, type);
    }

    /**
     * 将 JSON 对象字符串反序列化为 Map<String, V>。
     *
     * @param json JSON 对象字符串
     * @param valueClass Value 类型
     * @param <V> Value 泛型
     * @return Map 结果
     */
    public static <V> Map<String, V> fromJsonToMap(String json, Class<V> valueClass) {
        Type type = TypeToken.getParameterized(Map.class, String.class, valueClass).getType();
        return GSON.fromJson(json, type);
    }

    /**
     * 将 JSON 对象字符串反序列化为 Map<K, V>。
     * 在 Key 不是 String 的场景下也能复用统一的泛型类型构造逻辑。
     *
     * @param json JSON 对象字符串
     * @param keyClass Key 类型
     * @param valueClass Value 类型
     * @param <K> Key 泛型
     * @param <V> Value 泛型
     * @return Map 结果
     */
    public static <K, V> Map<K, V> fromJsonToMap(String json, Class<K> keyClass, Class<V> valueClass) {
        Type type = TypeToken.getParameterized(Map.class, keyClass, valueClass).getType();
        return GSON.fromJson(json, type);
    }

    // ==================== 高级功能 ====================

    /**
     * 深拷贝对象。
     * 通过序列化再反序列化复用统一的字段可见性和类型转换行为，适合普通 DTO 的快速复制。
     *
     * @param source 原对象
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 深拷贝结果
     */
    public static <T> T deepCopy(T source, Class<T> clazz) {
        String json = toJson(source);
        return fromJson(json, clazz);
    }

    /**
     * 深拷贝泛型对象。
     *
     * @param source 原对象
     * @param type 目标类型
     * @param <T> 泛型类型
     * @return 深拷贝结果
     */
    public static <T> T deepCopy(T source, Type type) {
        String json = toJson(source);
        return fromJson(json, type);
    }

    /**
     * 获取默认 Gson 实例。
     * 供少量需要直接操作 Gson API 的场景复用统一配置。
     *
     * @return 默认 Gson 实例
     */
    public static Gson getGson() {
        return GSON;
    }

    /**
     * 获取允许序列化 null 的 Gson 实例。
     *
     * @return 允许序列化 null 的 Gson 实例
     */
    public static Gson getGsonWithNulls() {
        return GSON_NULL;
    }
}
