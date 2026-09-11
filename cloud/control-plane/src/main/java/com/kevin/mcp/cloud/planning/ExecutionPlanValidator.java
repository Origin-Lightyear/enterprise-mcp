package com.kevin.mcp.cloud.planning;

import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.util.GsonUtil;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验 LLM 计划的协议结构、引用关系和 Catalog 能力白名单。
 *
 * @author Kevin
 * @date 2026-09-10
 */
@Component
public class ExecutionPlanValidator {

    private static final Type OBJECT_TYPE = new TypeToken<Object>() { }.getType();
    private static final Set<String> PLAN_FIELDS = Set.of("intent", "steps", "error");
    private static final Set<String> CALL_FIELDS = Set.of(
            "type", "id", "capabilityId", "capabilityVersion", "args", "saveAs", "dependsOn");
    private static final Set<String> FOREACH_FIELDS = Set.of(
            "type", "id", "itemsFrom", "itemAs", "body", "saveAs", "dependsOn");

    /**
     * 校验计划只能调用 Catalog 中的能力并符合当前执行协议。
     *
     * @param plan LLM 返回的计划对象
     * @param expectedIntent 原始用户意图
     * @param catalogJson ACTIVE Catalog JSON
     */
    public void validate(Map<String, Object> plan, String expectedIntent, String catalogJson) {
        requireAllowedFields(plan, PLAN_FIELDS, "plan");
        if (!expectedIntent.equals(requireText(plan.get("intent"), "intent"))) {
            throw new IllegalStateException("LLM plan intent does not match the request");
        }
        List<?> steps = requireList(plan.get("steps"), "steps");
        String error = optionalText(plan.get("error"));
        if (error != null) {
            if (!steps.isEmpty()) {
                throw new IllegalStateException("Failed plan must not contain executable steps");
            }
            return;
        }
        if (steps.isEmpty()) {
            throw new IllegalStateException("Executable plan steps are empty");
        }
        Map<String, CapabilityDefinition> capabilities = readCapabilities(catalogJson);
        validateSteps(steps, capabilities, Set.of(), false, "steps");
    }

    private void validateSteps(List<?> rawSteps, Map<String, CapabilityDefinition> capabilities,
                               Set<String> outerNames, boolean insideForeach, String path) {
        Set<String> declaredNames = new HashSet<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object rawStep : rawSteps) {
            Map<String, Object> step = requireObject(rawStep, path + " item");
            steps.add(step);
            registerName(requireText(step.get("id"), path + ".id"), declaredNames, path);
            String saveAs = optionalText(step.get("saveAs"));
            if (saveAs != null) {
                registerName(saveAs, declaredNames, path);
            }
        }

        Set<String> visibleNames = new HashSet<>(outerNames);
        for (Map<String, Object> step : steps) {
            String type = requireText(step.get("type"), path + ".type");
            validateDependencies(step.get("dependsOn"), visibleNames);
            if ("call".equals(type)) {
                validateCall(step, capabilities, visibleNames, insideForeach, path);
            } else if ("foreach".equals(type)) {
                if (insideForeach) {
                    throw new IllegalStateException("Nested foreach is not supported");
                }
                validateForeach(step, capabilities, visibleNames, path);
            } else {
                throw new IllegalStateException("Unsupported plan step type: " + type);
            }
            visibleNames.add(String.valueOf(step.get("id")));
            String saveAs = optionalText(step.get("saveAs"));
            if (saveAs != null) {
                visibleNames.add(saveAs);
            }
        }
    }

    private void validateCall(Map<String, Object> step, Map<String, CapabilityDefinition> capabilities,
                              Set<String> visibleNames, boolean insideForeach, String path) {
        requireAllowedFields(step, CALL_FIELDS, path + ".call");
        if (step.containsKey("methodKey")) {
            throw new IllegalStateException("Cloud plan must not contain methodKey");
        }
        String capabilityId = requireText(step.get("capabilityId"), "capabilityId");
        CapabilityDefinition capability = capabilities.get(capabilityId);
        if (capability == null) {
            throw new IllegalStateException("Plan references unknown capability: " + capabilityId);
        }
        int capabilityVersion = requireInteger(step.get("capabilityVersion"), "capabilityVersion");
        if (capability.version() != capabilityVersion) {
            throw new IllegalStateException("Capability version mismatch: " + capabilityId);
        }
        Map<String, Object> args = requireObject(step.get("args"), "args");
        validateArguments(args, capability.inputSchema(), visibleNames, insideForeach, capabilityId);
    }

    private void validateForeach(Map<String, Object> step, Map<String, CapabilityDefinition> capabilities,
                                 Set<String> visibleNames, String path) {
        requireAllowedFields(step, FOREACH_FIELDS, path + ".foreach");
        validateReference(requireText(step.get("itemsFrom"), "itemsFrom"), visibleNames, "itemsFrom");
        String itemAs = requireText(step.get("itemAs"), "itemAs");
        List<?> body = requireList(step.get("body"), "body");
        if (body.isEmpty()) {
            throw new IllegalStateException("foreach body is empty");
        }
        for (Object rawBodyStep : body) {
            Map<String, Object> bodyStep = requireObject(rawBodyStep, "foreach body");
            if (!"call".equals(bodyStep.get("type"))) {
                throw new IllegalStateException("foreach body only supports call steps");
            }
        }
        Set<String> bodyVisibleNames = new HashSet<>(visibleNames);
        bodyVisibleNames.add(itemAs);
        validateSteps(body, capabilities, bodyVisibleNames, true, path + ".body");
    }

    private void validateArguments(Map<String, Object> args, Map<String, Object> schema,
                                   Set<String> visibleNames, boolean insideForeach, String capabilityId) {
        Map<String, Object> properties = propertySchemas(schema.get("properties"));
        for (String required : stringList(schema.get("required"))) {
            if (!args.containsKey(required)) {
                throw new IllegalStateException("Missing required argument for " + capabilityId + ": " + required);
            }
        }
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            for (String argumentName : args.keySet()) {
                if (!properties.containsKey(argumentName)) {
                    throw new IllegalStateException("Unknown argument for " + capabilityId + ": " + argumentName);
                }
            }
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            validateArgumentValue(entry.getValue(), objectOrEmpty(properties.get(entry.getKey())),
                    visibleNames, insideForeach, capabilityId + "." + entry.getKey());
        }
    }

    private void validateArgumentValue(Object value, Map<String, Object> schema, Set<String> visibleNames,
                                       boolean insideForeach, String path) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> valueMap = normalizeMap(rawMap);
            if (valueMap.size() == 1 && valueMap.containsKey("fromStep")) {
                validateReference(requireText(valueMap.get("fromStep"), "fromStep"), visibleNames, "fromStep");
                return;
            }
            if (valueMap.size() == 1 && valueMap.containsKey("fromItem")) {
                if (!insideForeach) {
                    throw new IllegalStateException("fromItem can only be used inside foreach body");
                }
                requireText(valueMap.get("fromItem"), "fromItem");
                return;
            }
        }
        String expectedType = optionalText(schema.get("type"));
        if (expectedType != null && !matchesType(value, expectedType)) {
            throw new IllegalStateException("Argument type mismatch at " + path + ": expected " + expectedType);
        }
        List<?> enumValues = schema.get("enumValues") instanceof List<?> values
                ? values : schema.get("enum") instanceof List<?> values ? values : List.of();
        if (!enumValues.isEmpty() && enumValues.stream().noneMatch(item -> String.valueOf(item).equals(String.valueOf(value)))) {
            throw new IllegalStateException("Argument value is outside enum at " + path);
        }
        if (value instanceof Map<?, ?> rawObject && "object".equals(expectedType)) {
            Map<String, Object> object = normalizeMap(rawObject);
            Map<String, Object> properties = propertySchemas(schema.get("properties"));
            for (String required : stringList(schema.get("required"))) {
                if (!object.containsKey(required)) {
                    throw new IllegalStateException("Missing required object field at " + path + ": " + required);
                }
            }
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                for (String field : object.keySet()) {
                    if (!properties.containsKey(field)) {
                        throw new IllegalStateException("Unknown object field at " + path + ": " + field);
                    }
                }
            }
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                validateArgumentValue(entry.getValue(), objectOrEmpty(properties.get(entry.getKey())),
                        visibleNames, insideForeach, path + "." + entry.getKey());
            }
        }
        if (value instanceof List<?> list && "array".equals(expectedType)) {
            Map<String, Object> itemSchema = objectOrEmpty(schema.get("items"));
            for (int index = 0; index < list.size(); index++) {
                validateArgumentValue(list.get(index), itemSchema, visibleNames, insideForeach,
                        path + "[" + index + "]");
            }
        }
    }

    private boolean matchesType(Object value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Number number && number.doubleValue() == number.longValue();
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            case "null" -> value == null;
            default -> true;
        };
    }

    private Map<String, CapabilityDefinition> readCapabilities(String catalogJson) {
        if (catalogJson == null || catalogJson.isBlank()) {
            throw new IllegalStateException("Planning Catalog is empty");
        }
        Object root = GsonUtil.fromJson(catalogJson, OBJECT_TYPE);
        List<?> capabilityList;
        if (root instanceof List<?> list) {
            capabilityList = list;
        } else if (root instanceof Map<?, ?> map && map.get("capabilities") instanceof List<?> list) {
            capabilityList = list;
        } else {
            throw new IllegalStateException("Planning Catalog must contain a capability array");
        }
        Map<String, CapabilityDefinition> capabilities = new HashMap<>();
        for (Object rawCapability : capabilityList) {
            Map<String, Object> item = requireObject(rawCapability, "catalog capability");
            String id = requireText(item.get("capabilityId"), "catalog capabilityId");
            Object rawVersion = item.containsKey("capabilityVersion")
                    ? item.get("capabilityVersion") : item.get("version");
            int version = requireInteger(rawVersion, "catalog capabilityVersion");
            Map<String, Object> inputSchema = item.containsKey("parameters")
                    ? requireObject(item.get("parameters"), "catalog parameters")
                    : requireObject(item.get("inputSchema"), "catalog inputSchema");
            if (capabilities.putIfAbsent(id, new CapabilityDefinition(version, inputSchema)) != null) {
                throw new IllegalStateException("Duplicate Catalog capability: " + id);
            }
        }
        return capabilities;
    }

    private void validateDependencies(Object value, Set<String> visibleNames) {
        if (value == null) {
            return;
        }
        for (Object dependency : requireList(value, "dependsOn")) {
            String name = requireText(dependency, "dependsOn item");
            if (!visibleNames.contains(name)) {
                throw new IllegalStateException("Unknown dependency: " + name);
            }
        }
    }

    private void validateReference(String reference, Set<String> visibleNames, String fieldName) {
        String root = reference.split("\\.", 2)[0];
        if (root.isBlank() || !visibleNames.contains(root)) {
            throw new IllegalStateException("Unknown " + fieldName + " target: " + reference);
        }
    }

    private void registerName(String name, Set<String> names, String path) {
        if (!names.add(name)) {
            throw new IllegalStateException("Duplicate step name at " + path + ": " + name);
        }
    }

    private void requireAllowedFields(Map<String, Object> source, Set<String> allowedFields, String path) {
        for (String field : source.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new IllegalStateException("Unsupported field at " + path + ": " + field);
            }
        }
    }

    private Map<String, Object> requireObject(Object value, String fieldName) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException(fieldName + " must be an object");
        }
        return normalizeMap(map);
    }

    private Map<String, Object> objectOrEmpty(Object value) {
        return value instanceof Map<?, ?> map ? normalizeMap(map) : Map.of();
    }

    private Map<String, Object> propertySchemas(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (!(value instanceof List<?> properties)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Object propertyValue : properties) {
            Map<String, Object> property = requireObject(propertyValue, "Schema property");
            String name = requireText(property.get("name"), "Schema property name");
            Object propertySchema = property.get("schema");
            if (!(propertySchema instanceof Map<?, ?>)) {
                throw new IllegalStateException("Schema property definition must be an object: " + name);
            }
            result.put(name, propertySchema);
        }
        return result;
    }

    private Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private List<?> requireList(Object value, String fieldName) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(fieldName + " must be an array");
        }
        return list;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private String requireText(Object value, String fieldName) {
        String text = optionalText(value);
        if (text == null) {
            throw new IllegalStateException("Missing required field: " + fieldName);
        }
        return text;
    }

    private String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private int requireInteger(Object value, String fieldName) {
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue()) {
            throw new IllegalStateException(fieldName + " must be an integer");
        }
        return number.intValue();
    }

    /**
     * 保存规划阶段所需的最小能力定义。
     *
     * @param version 能力版本
     * @param inputSchema 能力输入 Schema
     */
    private record CapabilityDefinition(int version, Map<String, Object> inputSchema) {
    }
}
