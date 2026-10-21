package com.customer.mcp.k3.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用现场表单元数据动态翻译金蝶字段 Key 为用户可读列名和枚举码值。
 *
 * <p>翻译规则来自 QueryBusinessInfo 的 Name 多语言数组，lcid=2052 是简体中文；
 * 枚举字典来自字段 Extends 数组的 Value/Caption，全部为运行时数据，
 * 不维护任何静态字段或枚举映射文件，客户账套扩展自定义字段或修改枚举后无需改代码。
 * lookup 路径按精确 Key 优先，未命中时拆出基础字段并用后缀语义拼接，
 * 仍然无法识别时保留原始 Key，保证查询不被展示层翻译阻塞。</p>
 *
 * @author Kevin
 * @date 2026-10-19
 */
@Component
@ConditionalOnProperty(name = "enterprise.k3.enabled", havingValue = "true")
public class K3FieldLocalizer {

    /** 简体中文语言标识；金蝶 Name 数组使用该 Key 定位显示名。 */
    private static final int SIMPLIFIED_CHINESE_LCID = 2052;

    /** 本地映射缓存时长；与 K3Catalog 元数据缓存保持同一节奏，避免账套变更后长期陈旧。 */
    private static final long CACHE_TTL_MILLIS = 60_000;

    private final K3Catalog catalog;
    private final ConcurrentHashMap<String, CachedMetadata> cache = new ConcurrentHashMap<>();

    /**
     * 注入现场目录；元数据可用性由目录层负责，这里只做展示翻译。
     *
     * @param catalog 金蝶现场表单目录
     */
    public K3FieldLocalizer(K3Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 翻译逗号分隔 FieldKeys，输出顺序与请求字段一致的展示列名。
     *
     * @param formId 金蝶表单标识
     * @param fieldKeys 逗号分隔字段 Key
     * @return 展示列名列表；元数据缺失时逐项回落原始 Key
     */
    public List<String> fieldNames(String formId, String fieldKeys) {
        return fieldNames(formId, List.of(fieldKeys.split(",")));
    }

    /**
     * 翻译字段 Key 列表，输出顺序与请求字段一致的展示列名。
     *
     * @param formId 金蝶表单标识
     * @param fieldKeys 字段 Key 列表
     * @return 展示列名列表；元数据缺失时逐项回落原始 Key
     */
    public List<String> fieldNames(String formId, List<String> fieldKeys) {
        Map<String, String> names = cache(formId).names();
        List<String> result = new ArrayList<>(fieldKeys.size());
        for (String fieldKey : fieldKeys) {
            result.add(displayName(names, fieldKey.trim()));
        }
        return List.copyOf(result);
    }

    /**
     * 读取当前账套枚举字段码值到显示名的字典。
     *
     * @param formId 金蝶表单标识
     * @return 字段 Key 到码值字典的映射；元数据不可用或字段不是枚举时返回空字典
     */
    public Map<String, Map<String, String>> enumDictionaries(String formId) {
        return cache(formId).enumDictionaries();
    }

    /**
     * 读取并解析当前账套元数据，字段名与枚举字典共用同一份缓存。
     *
     * @param formId 金蝶表单标识
     * @return 缓存的名称与枚举映射；元数据探测失败时返回空映射而不是中断查询
     */
    private CachedMetadata cache(String formId) {
        long now = System.currentTimeMillis();
        CachedMetadata cached = cache.get(formId);
        if (cached != null && cached.expiresAt() > now) {
            return cached;
        }
        CachedMetadata parsed;
        try {
            JsonObject metadata = catalog.describe(formId);
            parsed = new CachedMetadata(parseNames(metadata), parseEnumDictionaries(metadata),
                    now + CACHE_TTL_MILLIS);
        } catch (RuntimeException exception) {
            // 元数据探测失败只影响列头和枚举展示；查询本身可能仍然有效，不应因此中断业务结果。
            parsed = new CachedMetadata(Map.of(), Map.of(), now + CACHE_TTL_MILLIS);
        }
        cache.put(formId, parsed);
        return parsed;
    }

    /**
     * 从 QueryBusinessInfo 响应提取所有字段 Key 和简体中文名。
     *
     * @param metadata 金蝶元数据 JSON
     * @return Key 到中文名映射
     */
    private static Map<String, String> parseNames(JsonObject metadata) {
        Map<String, String> names = new ConcurrentHashMap<>();
        for (JsonObject field : metadataFields(metadata)) {
            String name = localizedName(field.get("Name"));
            if (!name.isBlank()) {
                names.put(field.get("Key").getAsString(), name);
            }
        }
        return names;
    }

    /**
     * 从 QueryBusinessInfo 响应提取枚举字段的码值字典。
     *
     * @param metadata 金蝶元数据 JSON
     * @return 字段 Key 到码值字典的映射；无 Extends 数据的字段不出现
     */
    private static Map<String, Map<String, String>> parseEnumDictionaries(JsonObject metadata) {
        Map<String, Map<String, String>> dictionaries = new ConcurrentHashMap<>();
        for (JsonObject field : metadataFields(metadata)) {
            if (!field.has("Extends") || !field.get("Extends").isJsonArray()) {
                continue;
            }
            Map<String, String> values = new ConcurrentHashMap<>();
            for (JsonElement item : field.getAsJsonArray("Extends")) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject option = item.getAsJsonObject();
                if (!option.has("Value") || option.get("Value").isJsonNull()
                        || !option.has("Caption") || option.get("Caption").isJsonNull()) {
                    continue;
                }
                String value = option.get("Value").getAsString();
                String caption = option.get("Caption").getAsString();
                if (!value.isBlank() && !caption.isBlank()) {
                    values.put(value, caption);
                }
            }
            if (!values.isEmpty()) {
                dictionaries.put(field.get("Key").getAsString(), values);
            }
        }
        return dictionaries;
    }

    /**
     * 遍历元数据中所有实体字段节点。
     *
     * @param metadata 金蝶元数据 JSON
     * @return 字段节点列表
     */
    private static List<JsonObject> metadataFields(JsonObject metadata) {
        List<JsonObject> fields = new ArrayList<>();
        JsonObject result = metadata.has("Result") && metadata.get("Result").isJsonObject()
                ? metadata.getAsJsonObject("Result") : null;
        JsonObject model = result != null && result.has("NeedReturnData")
                && result.get("NeedReturnData").isJsonObject()
                ? result.getAsJsonObject("NeedReturnData") : null;
        if (model == null || !model.has("Entrys") || !model.get("Entrys").isJsonArray()) {
            return fields;
        }
        for (JsonElement entryElement : model.getAsJsonArray("Entrys")) {
            if (!entryElement.isJsonObject() || !entryElement.getAsJsonObject().has("Fields")
                    || !entryElement.getAsJsonObject().get("Fields").isJsonArray()) {
                continue;
            }
            for (JsonElement fieldElement : entryElement.getAsJsonObject().getAsJsonArray("Fields")) {
                if (fieldElement.isJsonObject() && fieldElement.getAsJsonObject().has("Key")
                        && !fieldElement.getAsJsonObject().get("Key").isJsonNull()) {
                    fields.add(fieldElement.getAsJsonObject());
                }
            }
        }
        return fields;
    }

    /**
     * 按精确 Key 优先翻译；lookup 路径未命中时用基础字段名加后缀语义拼接。
     *
     * @param names 现场字段名称映射
     * @param fieldKey 当前字段 Key
     * @return 展示列名；无法识别时保留原始 Key
     */
    private static String displayName(Map<String, String> names, String fieldKey) {
        String exact = names.get(fieldKey);
        if (exact != null && !exact.isBlank()) {
            return exact;
        }
        int separator = fieldKey.indexOf('.');
        if (separator <= 0 || separator >= fieldKey.length() - 1) {
            return fieldKey;
        }
        String baseName = names.get(fieldKey.substring(0, separator));
        if (baseName == null || baseName.isBlank()) {
            return fieldKey;
        }
        String suffix = fieldKey.substring(separator);
        if (".FName".equals(suffix)) {
            return baseName + "名称";
        }
        if (".FNumber".equals(suffix)) {
            return baseName + "编码";
        }
        return fieldKey;
    }

    /**
     * 提取金蝶 Name 多语言数组中的简体中文值。
     *
     * @param value Name 字段值
     * @return 简体中文名；无值时返回空串
     */
    private static String localizedName(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonObject() && item.getAsJsonObject().has("Key")
                        && item.getAsJsonObject().get("Key").getAsInt() == SIMPLIFIED_CHINESE_LCID
                        && item.getAsJsonObject().has("Value")
                        && !item.getAsJsonObject().get("Value").isJsonNull()) {
                    return item.getAsJsonObject().get("Value").getAsString();
                }
            }
        }
        return "";
    }

    /**
     * 带过期时间的现场元数据展示缓存项。
     *
     * @param names Key 到中文名映射
     * @param enumDictionaries 字段 Key 到码值字典映射
     * @param expiresAt 过期时间戳
     */
    private record CachedMetadata(Map<String, String> names,
                                  Map<String, Map<String, String>> enumDictionaries,
                                  long expiresAt) {
    }
}
