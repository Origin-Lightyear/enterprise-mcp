package com.kevin.mcp.execution;

import com.kevin.mcp.catalog.RuntimeCatalog;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionResult;
import com.kevin.mcp.processor.JsonExecutionStep;
import com.kevin.mcp.util.GsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * 执行结构化 JSON 调用计划。
 * 同时支持单步调用、多步串联与 foreach 循环，尽量把引用解析和循环上下文收敛在统一执行器中。
 *
 * @author Kevin
 * @date 2026-08-02
 */
@Component
public class JsonPlanExecutor {

    private final PrivateMcpToolSchemaRegistry registry;
    private ExecutionLimitsProperties limits;
    private RuntimeCatalog runtimeCatalog;

    @Autowired
    public JsonPlanExecutor(PrivateMcpToolSchemaRegistry registry) {
        this.registry = registry;
        this.limits = new ExecutionLimitsProperties();
    }

    /** 覆盖默认预算为客户配置值；测试或旧上下文未提供配置时保留默认值。 */
    @Autowired(required = false)
    public void configureLimits(ExecutionLimitsProperties configuredLimits) {
        if (configuredLimits != null) {
            this.limits = configuredLimits;
        }
    }

    /**
     * 注入 Runtime Catalog 检查点；仅不启动 Spring 的执行器单元测试允许缺省。
     *
     * @param runtimeCatalogProvider Runtime Catalog provider
     */
    @Autowired(required = false)
    public void configureRuntimeCatalog(ObjectProvider<RuntimeCatalog> runtimeCatalogProvider) {
        this.runtimeCatalog = runtimeCatalogProvider.getIfAvailable();
    }

    /** 注入执行资源预算。 */
    public JsonPlanExecutor(PrivateMcpToolSchemaRegistry registry, ExecutionLimitsProperties limits) {
        this.registry = registry;
        this.limits = limits == null ? new ExecutionLimitsProperties() : limits;
    }

    /**
     * 按步骤顺序执行整份调用计划。
     *
     * @param plan 待执行计划
     * @return 执行结果汇总
     */
    public JsonExecutionResult execute(JsonExecutionPlan plan) {
        return execute(plan, ExecutionProgressListener.NONE);
    }

    /**
     * 按步骤顺序执行整份调用计划，并发送业务级进度事件。
     *
     * @param plan 待执行计划
     * @param progressListener 进度监听器
     * @return 执行结果汇总
     */
    public JsonExecutionResult execute(JsonExecutionPlan plan, ExecutionProgressListener progressListener) {
        if (plan == null) {
            throw new IllegalArgumentException("Plan must not be null");
        }
        requireCurrent(plan);
        ExecutionProgressListener listener = progressListener == null
                ? ExecutionProgressListener.NONE : progressListener;
        int declaredSteps = countSteps(plan.steps());
        if (declaredSteps > limits.getMaxSteps()) {
            throw new IllegalArgumentException("Plan exceeds maximum steps: " + limits.getMaxSteps());
        }
        long deadline = System.nanoTime() + limits.getTimeout().toNanos();
        Map<String, Object> stepResults = new LinkedHashMap<>();
        List<String> executedSteps = new ArrayList<>();
        listener.planStarted(plan.steps().size());
        Object finalResult = this.executeSteps(plan, plan.steps(), stepResults, null, executedSteps,
                true, deadline, listener);
        verifyActive(deadline);
        requireCurrent(plan);
        if (GsonUtil.toJson(finalResult).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limits.getMaxResultBytes()) {
            throw new IllegalStateException("Plan result exceeds maximum bytes");
        }
        JsonExecutionResult result = new JsonExecutionResult(
                plan.planId(),
                plan.intent(),
                true,
                finalResult,
                Map.copyOf(stepResults),
                List.copyOf(executedSteps)
        );
        listener.planCompleted();
        return result;
    }

    /**
     * 顺序执行当前作用域内的一组步骤。
     *
     * @param steps 步骤列表
     * @param visibleValues 当前作用域可见结果集
     * @param currentItem 当前循环元素；不在 foreach 中时为 null
     * @param executedSteps 执行轨迹
     * @param recordExecutedSteps 是否记录当前层级步骤到执行轨迹
     * @param deadline 当前计划的单调时钟截止时间
     * @param progressListener 业务级进度监听器
     * @return 最后一个步骤的执行结果
     */
    private Object executeSteps(
            JsonExecutionPlan plan,
            List<JsonExecutionStep> steps,
            Map<String, Object> visibleValues,
            Object currentItem,
            List<String> executedSteps,
            boolean recordExecutedSteps,
            long deadline,
            ExecutionProgressListener progressListener
    ) {
        Object finalResult = null;
        for (JsonExecutionStep step : steps) {
            verifyActive(deadline);
            requireCurrent(plan);
            if (recordExecutedSteps) {
                progressListener.stepStarted(executedSteps.size() + 1, steps.size());
            }
            this.verifyDependencies(step, visibleValues);
            finalResult = this.executeStep(plan, step, visibleValues, currentItem, executedSteps, deadline,
                    progressListener);
            requireCurrent(plan);
            visibleValues.put(step.id(), finalResult);
            if (step.saveAs() != null) {
                visibleValues.put(step.saveAs(), finalResult);
            }
            if (recordExecutedSteps) {
                executedSteps.add(step.id());
            }
        }
        return finalResult;
    }

    /**
     * 执行单个步骤。
     *
     * @param step 当前步骤
     * @param visibleValues 当前作用域可见结果集
     * @param currentItem 当前循环元素
     * @param executedSteps 执行轨迹
     * @param deadline 当前计划的单调时钟截止时间
     * @param progressListener 业务级进度监听器
     * @return 当前步骤结果
     */
    private Object executeStep(
            JsonExecutionPlan plan,
            JsonExecutionStep step,
            Map<String, Object> visibleValues,
            Object currentItem,
            List<String> executedSteps,
            long deadline,
            ExecutionProgressListener progressListener
    ) {
        if (step.isCall()) {
            return this.invokeCall(step, visibleValues, currentItem);
        }
        if (step.isForeach()) {
            return this.executeForeach(plan, step, visibleValues, currentItem, executedSteps, deadline,
                    progressListener);
        }
        throw new IllegalArgumentException("Unsupported step type: " + step.type());
    }

    /**
     * 校验当前步骤声明的依赖已经可见。
     *
     * @param step 当前步骤
     * @param visibleValues 当前作用域可见结果集
     */
    private void verifyDependencies(JsonExecutionStep step, Map<String, Object> visibleValues) {
        for (String dependency : step.dependsOn()) {
            if (!visibleValues.containsKey(dependency)) {
                throw new IllegalArgumentException("Dependency has not been executed: " + dependency);
            }
        }
    }

    /**
     * 执行普通内部方法调用。
     *
     * @param step 调用步骤
     * @param visibleValues 当前作用域可见结果集
     * @param currentItem 当前循环元素
     * @return 调用结果
     */
    private Object invokeCall(JsonExecutionStep step, Map<String, Object> visibleValues, Object currentItem) {
        Method method;
        Object bean;
        if (step.capabilityId() != null && !step.capabilityId().isBlank()) {
            if (step.capabilityVersion() != null) {
                int localVersion = this.registry.getCapability(step.capabilityId())
                        .orElseThrow(() -> new IllegalArgumentException("Capability not cached: " + step.capabilityId()))
                        .version();
                if (localVersion != step.capabilityVersion()) {
                    throw new IllegalArgumentException("Capability version mismatch: " + step.capabilityId());
                }
            }
            method = this.registry.getRegisteredMethodByCapabilityId(step.capabilityId())
                    .orElseThrow(() -> new IllegalArgumentException("Capability not cached: " + step.capabilityId()));
            bean = this.registry.getRegisteredBeanByCapabilityId(step.capabilityId())
                    .orElseThrow(() -> new IllegalArgumentException("Capability bean not found: " + step.capabilityId()));
        } else {
            method = this.registry.getRegisteredMethod(step.methodKey())
                    .orElseThrow(() -> new IllegalArgumentException("Method not cached: " + step.methodKey()));
            bean = this.registry.getRegisteredBean(step.methodKey())
                    .orElseThrow(() -> new IllegalArgumentException("Bean not found: " + step.methodKey()));
        }
        Object[] arguments = this.resolveArguments(method, step.args(), visibleValues, currentItem);
        try {
            ReflectionUtils.makeAccessible(method);
            return method.invoke(bean, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Failed to invoke step: " + step.id(), cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to invoke step: " + step.id(), exception);
        }
    }

    /**
     * 执行 foreach 步骤，并聚合每轮循环体最后一步的结果。
     *
     * @param step 循环步骤
     * @param visibleValues 当前作用域可见结果集
     * @param currentItem 当前外层循环元素
     * @param executedSteps 执行轨迹
     * @param deadline 当前计划的单调时钟截止时间
     * @param progressListener 业务级进度监听器
     * @return 聚合后的循环结果列表
     */
    private Object executeForeach(
            JsonExecutionPlan plan,
            JsonExecutionStep step,
            Map<String, Object> visibleValues,
            Object currentItem,
            List<String> executedSteps,
            long deadline,
            ExecutionProgressListener progressListener
    ) {
        Object collection = this.resolveStepReference(step.itemsFrom(), visibleValues);
        List<Object> items = this.asItemList(collection, step.id());
        List<Object> loopResults = new ArrayList<>(items.size());
        if (items.size() > limits.getMaxLoopIterations()) {
            throw new IllegalArgumentException("foreach exceeds maximum iterations: " + limits.getMaxLoopIterations());
        }
        progressListener.foreachStarted(items.size());
        for (int index = 0; index < items.size(); index++) {
            verifyActive(deadline);
            requireCurrent(plan);
            Object item = items.get(index);
            Map<String, Object> iterationValues = new LinkedHashMap<>(visibleValues);
            iterationValues.put(step.itemAs(), item);
            Object iterationResult = this.executeSteps(plan, step.body(), iterationValues, item, executedSteps,
                    false, deadline, progressListener);
            requireCurrent(plan);
            loopResults.add(iterationResult);
            progressListener.foreachProgress(index + 1, items.size());
        }
        return List.copyOf(loopResults);
    }

    private int countSteps(List<JsonExecutionStep> steps) {
        int total = 0;
        for (JsonExecutionStep step : steps) {
            total += 1 + (step.isForeach() ? countSteps(step.body()) : 0);
        }
        return total;
    }

    private void verifyActive(long deadline) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Plan execution was cancelled");
        }
        if (System.nanoTime() > deadline) {
            throw new IllegalStateException("Plan execution timed out");
        }
    }

    private void requireCurrent(JsonExecutionPlan plan) {
        if (runtimeCatalog != null) {
            runtimeCatalog.requireExecutable(plan);
        }
    }

    /**
     * 将参数映射绑定为反射调用所需的位置参数数组。
     *
     * @param method 目标方法
     * @param argumentsMap 原始参数映射
     * @param visibleValues 当前作用域可见结果集
     * @param currentItem 当前循环元素
     * @return 可直接反射调用的位置参数数组
     */
    private Object[] resolveArguments(
            Method method,
            Map<String, Object> argumentsMap,
            Map<String, Object> visibleValues,
            Object currentItem
    ) {
        Parameter[] methodParameters = method.getParameters();
        Object[] arguments = new Object[methodParameters.length];
        for (int index = 0; index < methodParameters.length; index++) {
            Parameter parameter = methodParameters[index];
            if (!argumentsMap.containsKey(parameter.getName())) {
                throw new IllegalArgumentException("Missing parameter: " + parameter.getName());
            }
            Object resolvedValue = this.resolveParameterValue(argumentsMap.get(parameter.getName()), visibleValues, currentItem);
            arguments[index] = this.convertArgumentValue(resolvedValue, parameter.getType(), parameter.getName());
        }
        return arguments;
    }

    /**
     * 解析参数值中的 fromStep、fromItem、旧版 $ref 或嵌套对象。
     *
     * @param rawValue 原始参数值
     * @param visibleValues 当前作用域可见结果集
     * @param currentItem 当前循环元素
     * @return 解析后的参数值
     */
    private Object resolveParameterValue(Object rawValue, Map<String, Object> visibleValues, Object currentItem) {
        if (rawValue instanceof Map<?, ?> rawMap) {
            Map<String, Object> valueMap = this.toObjectMap(rawMap);
            if (valueMap.size() == 1 && valueMap.containsKey("fromStep")) {
                return this.resolveStepReference(String.valueOf(valueMap.get("fromStep")), visibleValues);
            }
            if (valueMap.size() == 1 && valueMap.containsKey("$ref")) {
                return this.resolveStepReference(String.valueOf(valueMap.get("$ref")), visibleValues);
            }
            if (valueMap.size() == 1 && valueMap.containsKey("fromItem")) {
                return this.resolveItemReference(String.valueOf(valueMap.get("fromItem")), currentItem);
            }
            Map<String, Object> resolvedMap = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : valueMap.entrySet()) {
                resolvedMap.put(entry.getKey(), this.resolveParameterValue(entry.getValue(), visibleValues, currentItem));
            }
            return resolvedMap;
        }
        if (rawValue instanceof List<?> rawList) {
            List<Object> resolvedList = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                resolvedList.add(this.resolveParameterValue(item, visibleValues, currentItem));
            }
            return resolvedList;
        }
        return rawValue;
    }

    /**
     * 解析前置步骤结果引用。
     *
     * @param reference 引用表达式
     * @param visibleValues 当前作用域可见结果集
     * @return 引用命中的值
     */
    private Object resolveStepReference(String reference, Map<String, Object> visibleValues) {
        String[] tokens = reference.split("\\.");
        Object current = visibleValues.get(tokens[0]);
        if (current == null) {
            throw new IllegalArgumentException("Reference step result not found: " + reference);
        }
        for (int index = 1; index < tokens.length; index++) {
            current = this.readFieldValue(current, tokens[index], reference);
        }
        return current;
    }

    /**
     * 解析当前循环元素字段引用。
     *
     * @param reference 字段路径
     * @param currentItem 当前循环元素
     * @return 命中的字段值
     */
    private Object resolveItemReference(String reference, Object currentItem) {
        if (currentItem == null) {
            throw new IllegalArgumentException("fromItem can only be used inside foreach body");
        }
        String[] tokens = reference.split("\\.");
        Object current = currentItem;
        for (String token : tokens) {
            current = this.readFieldValue(current, token, reference);
        }
        return current;
    }

    /**
     * 将集合来源归一化为可迭代列表。
     *
     * @param source 集合来源
     * @param stepId 当前步骤 ID
     * @return 可迭代列表
     */
    private List<Object> asItemList(Object source, String stepId) {
        if (source instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (source instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            for (Object item : iterable) {
                items.add(item);
            }
            return items;
        }
        if (source != null && source.getClass().isArray()) {
            int length = Array.getLength(source);
            List<Object> items = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                items.add(Array.get(source, index));
            }
            return items;
        }
        throw new IllegalArgumentException("foreach itemsFrom is not a collection: " + stepId);
    }

    /**
     * 按目标参数类型归一化基础值，重点处理 Gson 把整数解析为 Double 的场景。
     *
     * @param value 原始值
     * @param targetType 目标参数类型
     * @param parameterName 参数名，仅用于错误提示
     * @return 归一化后的参数值
     */
    private Object convertArgumentValue(Object value, Class<?> targetType, String parameterName) {
        if (value == null) {
            if (targetType.isPrimitive()) {
                throw new IllegalArgumentException("Primitive parameter cannot be null: " + parameterName);
            }
            return null;
        }
        Class<?> wrapperType = this.wrapPrimitiveType(targetType);
        if (wrapperType.isInstance(value)) {
            return value;
        }
        if (value instanceof Number number) {
            if (Long.class.equals(wrapperType)) {
                return number.longValue();
            }
            if (Integer.class.equals(wrapperType)) {
                return number.intValue();
            }
            if (Double.class.equals(wrapperType)) {
                return number.doubleValue();
            }
            if (Float.class.equals(wrapperType)) {
                return number.floatValue();
            }
            if (Short.class.equals(wrapperType)) {
                return number.shortValue();
            }
            if (Byte.class.equals(wrapperType)) {
                return number.byteValue();
            }
        }
        return value;
    }

    /**
     * 从 Map 或 Java Bean 中读取字段值。
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
     * 将原始 Map 归一化为字符串键映射。
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

    /**
     * 将基础类型转换为包装类型，便于统一做 instanceof 判断。
     *
     * @param type 原始类型
     * @return 包装类型或原类型本身
     */
    private Class<?> wrapPrimitiveType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (long.class.equals(type)) {
            return Long.class;
        }
        if (int.class.equals(type)) {
            return Integer.class;
        }
        if (double.class.equals(type)) {
            return Double.class;
        }
        if (float.class.equals(type)) {
            return Float.class;
        }
        if (short.class.equals(type)) {
            return Short.class;
        }
        if (byte.class.equals(type)) {
            return Byte.class;
        }
        if (boolean.class.equals(type)) {
            return Boolean.class;
        }
        if (char.class.equals(type)) {
            return Character.class;
        }
        return type;
    }
}
