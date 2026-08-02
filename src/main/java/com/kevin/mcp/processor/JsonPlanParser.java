package com.kevin.mcp.processor;

import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.util.GsonUtil;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析并校验模型返回的 JSON 执行计划。
 * 在执行前尽早拦截结构错误、非法引用与循环声明缺失，降低运行时才暴露问题的概率。
 *
 * @author Kevin
 * @date 2026-08-02
 */
@Component
public class JsonPlanParser {

    /**
     * 将原始 JSON 文本解析为结构化执行计划。
     *
     * @param json 模型返回的计划 JSON
     * @return 结构化执行计划
     */
    public JsonExecutionPlan parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Plan JSON is empty");
        }
        Type payloadType = new TypeToken<Map<String, Object>>() { }.getType();
        Map<String, Object> payload = GsonUtil.fromJson(json, payloadType);
        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("Plan JSON is empty");
        }
        String planId = this.stringValue(payload.get("planId"));
        String intent = this.requireText(payload.get("intent"), "intent");
        String error = this.stringValue(payload.get("error"));
        List<JsonExecutionStep> steps = this.toOptionalSteps(payload.get("steps"), "steps");
        if (steps.isEmpty() && (error == null || error.isBlank())) {
            throw new IllegalArgumentException("Plan steps are empty");
        }
        if (!steps.isEmpty()) {
            this.validateSteps(steps, Set.of(), null, "steps");
        }
        return new JsonExecutionPlan(planId, intent, steps, error);
    }

    /**
     * 将原始步骤数组递归转换为强类型步骤列表。
     *
     * @param rawSteps 原始步骤数组
     * @param fieldName 字段名，仅用于错误定位
     * @return 强类型步骤列表
     */
    private List<JsonExecutionStep> toSteps(Object rawSteps, String fieldName) {
        if (!(rawSteps instanceof List<?> stepList) || stepList.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " are empty");
        }
        List<JsonExecutionStep> steps = new ArrayList<>();
        for (Object stepObject : stepList) {
            if (!(stepObject instanceof Map<?, ?> rawStepMap)) {
                throw new IllegalArgumentException("Plan step must be an object");
            }
            Map<String, Object> stepMap = this.toObjectMap(rawStepMap);
            steps.add(this.toStep(stepMap));
        }
        return List.copyOf(steps);
    }

    /**
     * 读取可选步骤数组。
     * 失败计划允许 steps 为空，因此这里不直接把空数组视为解析失败。
     *
     * @param rawSteps 原始步骤数组
     * @param fieldName 字段名，仅用于错误定位
     * @return 步骤列表；为空时返回空集合
     */
    private List<JsonExecutionStep> toOptionalSteps(Object rawSteps, String fieldName) {
        if (rawSteps == null) {
            return List.of();
        }
        if (rawSteps instanceof List<?> stepList && stepList.isEmpty()) {
            return List.of();
        }
        return this.toSteps(rawSteps, fieldName);
    }

    /**
     * 将单个步骤对象转换为统一步骤模型。
     * 为兼容旧协议，允许 call 步骤继续使用 stepId、parameters、saveResultAs 字段名。
     *
     * @param stepMap 原始步骤对象
     * @return 强类型步骤
     */
    private JsonExecutionStep toStep(Map<String, Object> stepMap) {
        String type = this.stringValue(stepMap.get("type"));
        if (type == null && stepMap.containsKey("methodKey")) {
            type = "call";
        }
        if ("call".equalsIgnoreCase(type)) {
            return new JsonExecutionStep(
                    "call",
                    this.requireText(this.firstNonNull(stepMap.get("id"), stepMap.get("stepId")), "id"),
                    this.requireText(stepMap.get("methodKey"), "methodKey"),
                    this.toParameters(this.firstNonNull(stepMap.get("args"), stepMap.get("parameters"))),
                    this.stringValue(this.firstNonNull(stepMap.get("saveAs"), stepMap.get("saveResultAs"))),
                    this.toDependsOn(stepMap.get("dependsOn")),
                    null,
                    null,
                    List.of()
            );
        }
        if ("foreach".equalsIgnoreCase(type)) {
            return new JsonExecutionStep(
                    "foreach",
                    this.requireText(this.firstNonNull(stepMap.get("id"), stepMap.get("stepId")), "id"),
                    null,
                    Map.of(),
                    this.stringValue(this.firstNonNull(stepMap.get("saveAs"), stepMap.get("saveResultAs"))),
                    this.toDependsOn(stepMap.get("dependsOn")),
                    this.requireText(stepMap.get("itemsFrom"), "itemsFrom"),
                    this.requireText(stepMap.get("itemAs"), "itemAs"),
                    this.toSteps(stepMap.get("body"), "body")
            );
        }
        throw new IllegalArgumentException("Unsupported step type: " + type);
    }

    /**
     * 校验当前层级步骤列表的唯一性、依赖关系与引用合法性。
     *
     * @param steps 当前层级步骤列表
     * @param outerVisibleNames 外层可见的步骤 ID 与别名
     * @param itemAlias 当前 foreach 循环元素别名；顶层为 null
     * @param path 当前校验路径，仅用于错误定位
     */
    private void validateSteps(
            List<JsonExecutionStep> steps,
            Set<String> outerVisibleNames,
            String itemAlias,
            String path
    ) {
        Set<String> declaredNames = new HashSet<>();
        for (JsonExecutionStep step : steps) {
            this.registerStepName(step.id(), declaredNames, path + "." + step.id());
            if (step.saveAs() != null) {
                this.registerStepName(step.saveAs(), declaredNames, path + "." + step.id() + ".saveAs");
            }
        }

        Set<String> visibleNames = new HashSet<>(outerVisibleNames);
        for (JsonExecutionStep step : steps) {
            for (String dependency : step.dependsOn()) {
                if (!visibleNames.contains(dependency)) {
                    throw new IllegalArgumentException("Unknown dependency: " + dependency);
                }
            }
            if (step.isCall()) {
                this.validateParameterValue(step.args(), visibleNames, itemAlias);
            } else if (step.isForeach()) {
                this.validatePathReference(step.itemsFrom(), visibleNames, "itemsFrom");
                Set<String> bodyVisibleNames = new HashSet<>(visibleNames);
                bodyVisibleNames.add(step.itemAs());
                this.validateSteps(step.body(), bodyVisibleNames, step.itemAs(), path + "." + step.id() + ".body");
            } else {
                throw new IllegalArgumentException("Unsupported step type: " + step.type());
            }
            visibleNames.add(step.id());
            if (step.saveAs() != null) {
                visibleNames.add(step.saveAs());
            }
        }
    }

    /**
     * 校验参数值中的 fromStep、fromItem 或旧版 $ref 引用。
     *
     * @param value 参数值
     * @param visibleNames 当前作用域可见的步骤 ID 与别名
     * @param itemAlias 当前循环元素别名；不在 foreach 内时为 null
     */
    private void validateParameterValue(Object value, Set<String> visibleNames, String itemAlias) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> valueMap = this.toObjectMap(rawMap);
            if (valueMap.size() == 1 && valueMap.containsKey("fromStep")) {
                this.validatePathReference(this.requireText(valueMap.get("fromStep"), "fromStep"), visibleNames, "fromStep");
                return;
            }
            if (valueMap.size() == 1 && valueMap.containsKey("$ref")) {
                this.validatePathReference(this.requireText(valueMap.get("$ref"), "$ref"), visibleNames, "$ref");
                return;
            }
            if (valueMap.size() == 1 && valueMap.containsKey("fromItem")) {
                if (itemAlias == null) {
                    throw new IllegalArgumentException("fromItem can only be used inside foreach body");
                }
                this.requireText(valueMap.get("fromItem"), "fromItem");
                return;
            }
            for (Object nestedValue : valueMap.values()) {
                this.validateParameterValue(nestedValue, visibleNames, itemAlias);
            }
            return;
        }
        if (value instanceof List<?> rawList) {
            for (Object item : rawList) {
                this.validateParameterValue(item, visibleNames, itemAlias);
            }
        }
    }

    /**
     * 校验路径引用的首段必须落在当前可见作用域中。
     *
     * @param reference 路径引用
     * @param visibleNames 当前可见名称集合
     * @param fieldName 字段名，仅用于错误提示
     */
    private void validatePathReference(String reference, Set<String> visibleNames, String fieldName) {
        String[] tokens = reference.split("\\.");
        if (tokens.length == 0 || tokens[0].isBlank()) {
            throw new IllegalArgumentException(fieldName + " is invalid: " + reference);
        }
        if (!visibleNames.contains(tokens[0])) {
            throw new IllegalArgumentException("Reference target not found: " + reference);
        }
    }

    /**
     * 注册步骤 ID 或别名，避免同一作用域内出现歧义名称。
     *
     * @param name 待注册名称
     * @param declaredNames 当前作用域已声明名称
     * @param path 错误提示路径
     */
    private void registerStepName(String name, Set<String> declaredNames, String path) {
        if (!declaredNames.add(name)) {
            throw new IllegalArgumentException("Duplicate step name at " + path + ": " + name);
        }
    }

    /**
     * 读取参数对象并保持原始嵌套结构。
     *
     * @param rawParameters 原始参数对象
     * @return 参数映射
     */
    private Map<String, Object> toParameters(Object rawParameters) {
        if (!(rawParameters instanceof Map<?, ?> parameterMap)) {
            throw new IllegalArgumentException("Step args must be an object");
        }
        return Map.copyOf(this.toObjectMap(parameterMap));
    }

    /**
     * 读取依赖列表并归一化为不可变集合。
     *
     * @param rawDependsOn 原始依赖数组
     * @return 依赖名称列表
     */
    private List<String> toDependsOn(Object rawDependsOn) {
        if (rawDependsOn == null) {
            return List.of();
        }
        if (!(rawDependsOn instanceof List<?> dependencies)) {
            throw new IllegalArgumentException("dependsOn must be an array");
        }
        List<String> dependsOn = new ArrayList<>();
        for (Object dependency : dependencies) {
            dependsOn.add(this.requireText(dependency, "dependsOn item"));
        }
        return List.copyOf(dependsOn);
    }

    /**
     * 将原始 Map 归一化为字符串键的对象映射。
     *
     * @param rawMap 原始 Map
     * @return 归一化后的 Map
     */
    private Map<String, Object> toObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> normalizedMap = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            normalizedMap.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return normalizedMap;
    }

    /**
     * 读取可选文本字段。
     *
     * @param value 原始值
     * @return 去空白后的文本；为空时返回 null
     */
    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 读取必填文本字段。
     *
     * @param value 原始值
     * @param fieldName 字段名
     * @return 非空文本
     */
    private String requireText(Object value, String fieldName) {
        String text = this.stringValue(value);
        if (text == null) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        return text;
    }

    /**
     * 返回第一个非 null 值。
     *
     * @param first 第一候选值
     * @param second 第二候选值
     * @return 第一个非 null 值；若都为空则返回 null
     */
    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }
}
