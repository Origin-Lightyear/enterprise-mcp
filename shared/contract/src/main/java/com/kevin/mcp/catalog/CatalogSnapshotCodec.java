package com.kevin.mcp.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kevin.mcp.util.GsonUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 生成跨 Runtime 与 Control Plane 一致的 Catalog Snapshot JSON 和摘要。
 *
 * <p>对象字段按名称排序，数组保持领域顺序；调用方必须在传入前对无序领域集合使用稳定业务键排序。</p>
 *
 * @author Kevin
 * @date 2026-09-18
 */
public final class CatalogSnapshotCodec {

    private static final String DIGEST_PREFIX = "sha256:";

    private CatalogSnapshotCodec() {
    }

    /**
     * 将对象编码为 canonical UTF-8 JSON。
     *
     * @param value Catalog 内容
     * @return canonical JSON
     */
    public static String encode(Object value) {
        return canonicalize(GsonUtil.toJson(value));
    }

    /**
     * 规范化已有 JSON，拒绝无效内容。
     *
     * @param json 原始 JSON
     * @return canonical JSON
     * @throws IllegalArgumentException JSON 为空或语法无效
     */
    public static String canonicalize(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Catalog JSON is required");
        }
        return canonicalElement(JsonParser.parseString(json)).toString();
    }

    /**
     * 计算 canonical JSON 的 Catalog Digest。
     *
     * @param canonicalJson canonical JSON
     * @return {@code sha256:} 加 64 位小写十六进制摘要
     */
    public static String digest(String canonicalJson) {
        String normalized = canonicalize(canonicalJson);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return DIGEST_PREFIX + java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static JsonElement canonicalElement(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) {
                result.add(canonicalElement(item));
            }
            return result;
        }
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            Map<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                result.add(entry.getKey(), canonicalElement(entry.getValue()));
            }
            return result;
        }
        return value.deepCopy();
    }
}
