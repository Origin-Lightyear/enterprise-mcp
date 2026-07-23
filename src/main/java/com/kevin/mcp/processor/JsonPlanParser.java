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
 * 解析并校验模型返回的 JSON 调用计划。
 * 在执行前尽早拦截结构性错误，避免运行期才暴露缺失字段或非法依赖。
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
        List<JsonExecutionStep> steps = this.toSteps(payload.get("steps"));
        this.validateSteps(steps);
        return new JsonExecutionPlan(planId, intent, steps);
    }

    /**
     * 将原始步骤数组转换为强类型步骤列表。
     *
     * @param rawSteps 原始步骤数组
     * @return 强类型步骤列表
     */
    private List<JsonExecutionStep> toSteps(Object rawSteps) {
        if (!(rawSteps instanceof List<?> stepList) || stepList.isEmpty()) {
            throw new IllegalArgumentException("Plan steps are empty");
        }
        List<JsonExecutionStep> steps = new ArrayList<>();
        for (Object stepObject : stepList) {
            if (!(stepObject instanceof Map<?, ?> rawStepMap)) {
                throw new IllegalArgumentException("Plan step must be an object");
            }
            Map<String, Object> stepMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawStepMap.entrySet()) {
                stepMap.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            steps.add(new JsonExecutionStep(
                    this.requireText(stepMap.get("stepId"), "stepId"),
                    this.requireText(stepMap.get("methodKey"), "methodKey"),
                    this.toParameters(stepMap.get("parameters")),
                    this.stringValue(stepMap.get("saveResultAs")),
                    this.toDependsOn(stepMap.get("dependsOn"))
            ));
        }
        return steps;
    }

    /**
     * 校验步骤集合的一致性。
     *
     * @param steps 待校验步骤
     */
    private void validateSteps(List<JsonExecutionStep> steps) {
        Set<String> stepIds = new HashSet<>();
        for (JsonExecutionStep step : steps) {
            if (!stepIds.add(step.stepId())) {
                throw new IllegalArgumentException("Duplicate stepId: " + step.stepId());
            }
        }
        for (JsonExecutionStep step : steps) {
            for (String dependency : step.dependsOn()) {
                if (!stepIds.contains(dependency)) {
                    throw new IllegalArgumentException("Unknown dependency: " + dependency);
                }
            }
            for (Object parameterValue : step.parameters().values()) {
                this.validateReference(parameterValue, stepIds);
            }
        }
    }

    /**
     * 校验引用表达式格式与引用目标是否存在。
     *
     * @param parameterValue 参数值
     * @param stepIds 当前计划中的所有步骤 ID
     */
    private void validateReference(Object parameterValue, Set<String> stepIds) {
        if (!(parameterValue instanceof Map<?, ?> parameterMap) || !parameterMap.containsKey("$ref")) {
            return;
        }
        Object referenceValue = parameterMap.get("$ref");
        if (!(referenceValue instanceof String reference) || reference.isBlank()) {
            throw new IllegalArgumentException("Reference value is blank");
        }
        String[] tokens = reference.split("\\.");
        if (tokens.length == 0 || tokens[0].isBlank()) {
            throw new IllegalArgumentException("Reference is invalid: " + reference);
        }
        if (!stepIds.contains(tokens[0])) {
            throw new IllegalArgumentException("Reference step not found: " + reference);
        }
    }

    /**
     * 读取参数对象并保持原始键值结构。
     *
     * @param rawParameters 原始参数对象
     * @return 参数映射
     */
    private Map<String, Object> toParameters(Object rawParameters) {
        if (!(rawParameters instanceof Map<?, ?> parameterMap)) {
            throw new IllegalArgumentException("Step parameters must be an object");
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : parameterMap.entrySet()) {
            parameters.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return parameters;
    }

    /**
     * 读取依赖列表并归一化为空列表。
     *
     * @param rawDependsOn 原始依赖数组
     * @return 依赖步骤 ID 列表
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
     * 读取可选文本字段。
     *
     * @param value 原始值
     * @return 文本值或 null
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
}
