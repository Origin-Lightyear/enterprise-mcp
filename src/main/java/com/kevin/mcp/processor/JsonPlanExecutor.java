package com.kevin.mcp.processor;

import com.kevin.mcp.registry.PrivateMcpToolSchemaRegistry;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行结构化 JSON 调用计划。
 * 只依赖 methodKey 到注册表中定位 Spring Bean 与 Method，实现最小闭环调用。
 */
@Component
public class JsonPlanExecutor {

    private final PrivateMcpToolSchemaRegistry registry;

    public JsonPlanExecutor(PrivateMcpToolSchemaRegistry registry) {
        this.registry = registry;
    }

    /**
     * 按步骤顺序执行整个调用计划。
     *
     * @param plan 待执行计划
     * @return 执行结果汇总
     */
    public JsonExecutionResult execute(JsonExecutionPlan plan) {
        Map<String, Object> stepResults = new LinkedHashMap<>();
        List<String> executedSteps = new ArrayList<>();
        Object finalResult = null;
        for (JsonExecutionStep step : plan.steps()) {
            this.verifyDependencies(step, stepResults);
            finalResult = this.executeStep(step, stepResults);
            stepResults.put(step.stepId(), finalResult);
            if (step.saveResultAs() != null) {
                stepResults.put(step.saveResultAs(), finalResult);
            }
            executedSteps.add(step.stepId());
        }
        return new JsonExecutionResult(
                plan.planId(),
                plan.intent(),
                true,
                finalResult,
                Map.copyOf(stepResults),
                List.copyOf(executedSteps)
        );
    }

    /**
     * 校验当前步骤的前置依赖已经完成。
     *
     * @param step 当前步骤
     * @param stepResults 已执行结果
     */
    private void verifyDependencies(JsonExecutionStep step, Map<String, Object> stepResults) {
        for (String dependency : step.dependsOn()) {
            if (!stepResults.containsKey(dependency)) {
                throw new IllegalArgumentException("Dependency has not been executed: " + dependency);
            }
        }
    }

    /**
     * 执行单个步骤。
     *
     * @param step 当前步骤
     * @param stepResults 已执行结果
     * @return 当前步骤结果
     */
    private Object executeStep(JsonExecutionStep step, Map<String, Object> stepResults) {
        Method method = this.registry.getRegisteredMethod(step.methodKey())
                .orElseThrow(() -> new IllegalArgumentException("Method not cached: " + step.methodKey()));
        Object bean = this.registry.getRegisteredBean(step.methodKey())
                .orElseThrow(() -> new IllegalArgumentException("Bean not found: " + step.methodKey()));
        Object[] arguments = this.resolveArguments(method, step.parameters(), stepResults);
        try {
            ReflectionUtils.makeAccessible(method);
            return method.invoke(bean, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to invoke step: " + step.stepId(), exception);
        }
    }

    /**
     * 将参数映射绑定为反射调用所需的位置参数数组。
     *
     * @param method 目标方法
     * @param parameters 原始参数映射
     * @param stepResults 已执行结果
     * @return 可直接反射调用的参数数组
     */
    private Object[] resolveArguments(Method method, Map<String, Object> parameters, Map<String, Object> stepResults) {
        Parameter[] methodParameters = method.getParameters();
        Object[] arguments = new Object[methodParameters.length];
        for (int index = 0; index < methodParameters.length; index++) {
            Parameter parameter = methodParameters[index];
            if (!parameters.containsKey(parameter.getName())) {
                throw new IllegalArgumentException("Missing parameter: " + parameter.getName());
            }
            arguments[index] = this.resolveParameterValue(parameters.get(parameter.getName()), stepResults);
        }
        return arguments;
    }

    /**
     * 解析参数值中的引用表达式。
     *
     * @param rawValue 原始参数值
     * @param stepResults 已执行结果
     * @return 解引用后的参数值
     */
    private Object resolveParameterValue(Object rawValue, Map<String, Object> stepResults) {
        if (!(rawValue instanceof Map<?, ?> valueMap) || !valueMap.containsKey("$ref")) {
            return rawValue;
        }
        String reference = String.valueOf(valueMap.get("$ref"));
        return this.resolveReference(reference, stepResults);
    }

    /**
     * 解析形如 step1.orderId 的字段引用。
     *
     * @param reference 引用表达式
     * @param stepResults 已执行结果
     * @return 引用命中的值
     */
    private Object resolveReference(String reference, Map<String, Object> stepResults) {
        String[] tokens = reference.split("\\.");
        Object current = stepResults.get(tokens[0]);
        if (current == null) {
            throw new IllegalArgumentException("Reference step result not found: " + reference);
        }
        for (int index = 1; index < tokens.length; index++) {
            current = this.readFieldValue(current, tokens[index], reference);
        }
        return current;
    }

    /**
     * 从 Map 或普通 Java Bean 中读取字段值。
     *
     * @param source 当前对象
     * @param fieldName 字段名
     * @param reference 原始引用表达式
     * @return 字段值
     */
    private Object readFieldValue(Object source, String fieldName, String reference) {
        if (source instanceof Map<?, ?> sourceMap) {
            if (!sourceMap.containsKey(fieldName)) {
                throw new IllegalArgumentException("Reference field not found: " + reference);
            }
            return sourceMap.get(fieldName);
        }
        Method getter = ReflectionUtils.findMethod(source.getClass(), "get" + this.capitalize(fieldName));
        if (getter != null) {
            try {
                ReflectionUtils.makeAccessible(getter);
                return getter.invoke(source);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to read reference: " + reference, exception);
            }
        }
        Field field = ReflectionUtils.findField(source.getClass(), fieldName);
        if (field != null) {
            ReflectionUtils.makeAccessible(field);
            return ReflectionUtils.getField(field, source);
        }
        throw new IllegalArgumentException("Reference field not found: " + reference);
    }

    /**
     * 将字段名转换为 getter 所需的首字母大写形式。
     *
     * @param value 字段名
     * @return 首字母大写后的字段名
     */
    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
