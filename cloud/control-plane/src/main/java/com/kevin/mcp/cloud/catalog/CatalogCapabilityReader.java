package com.kevin.mcp.cloud.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析规划目录中的稳定能力编号与版本，用于发布校验和 Runtime 兼容性握手。
 *
 * @author Kevin
 * @date 2026-09-10
 */
final class CatalogCapabilityReader {

    private CatalogCapabilityReader() {
    }

    static Map<String, Integer> read(String catalogJson) {
        if (catalogJson == null || catalogJson.isBlank()) {
            throw new IllegalArgumentException("Planning Catalog JSON is required");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(catalogJson);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Planning Catalog JSON is invalid", exception);
        }
        JsonArray capabilities = capabilityArray(root);
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("Planning Catalog capabilities are empty");
        }
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (JsonElement capabilityElement : capabilities) {
            if (!capabilityElement.isJsonObject()) {
                throw new IllegalArgumentException("Planning Catalog capability must be an object");
            }
            JsonObject capability = capabilityElement.getAsJsonObject();
            String capabilityId = requiredText(capability, "capabilityId");
            JsonElement versionElement = capability.has("capabilityVersion")
                    ? capability.get("capabilityVersion") : capability.get("version");
            int version = requiredPositiveInteger(versionElement, capabilityId);
            if (!capability.has("inputSchema") && !capability.has("parameters")) {
                throw new IllegalArgumentException("Planning Catalog capability input Schema is required: " + capabilityId);
            }
            if (versions.putIfAbsent(capabilityId, version) != null) {
                throw new IllegalArgumentException("Duplicate Planning Catalog capability: " + capabilityId);
            }
        }
        return Map.copyOf(versions);
    }

    private static JsonArray capabilityArray(JsonElement root) {
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject() && root.getAsJsonObject().has("capabilities")
                && root.getAsJsonObject().get("capabilities").isJsonArray()) {
            return root.getAsJsonObject().getAsJsonArray("capabilities");
        }
        throw new IllegalArgumentException("Planning Catalog must contain a capability array");
    }

    private static String requiredText(JsonObject object, String fieldName) {
        JsonElement value = object.get(fieldName);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("Planning Catalog " + fieldName + " is required");
        }
        return value.getAsString().trim();
    }

    private static int requiredPositiveInteger(JsonElement value, String capabilityId) {
        try {
            int version = value == null ? 0 : value.getAsInt();
            if (version <= 0) {
                throw new IllegalArgumentException("Planning Catalog capability version must be positive: " + capabilityId);
            }
            return version;
        } catch (NumberFormatException | UnsupportedOperationException exception) {
            throw new IllegalArgumentException("Planning Catalog capability version is invalid: " + capabilityId,
                    exception);
        }
    }
}
