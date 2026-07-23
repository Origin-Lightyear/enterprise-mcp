package com.kevin.mcp.registry;

import com.kevin.mcp.annotation.PrivateMcpTool;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 注册并缓存私有 MCP 方法的元数据与 Schema 描述。
 * 在 Spring 单例初始化完成后统一扫描 {@link PrivateMcpTool}，避免业务方按需反射带来的重复开销与结果不一致问题。
 */
@Component
public class PrivateMcpToolSchemaRegistry implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(PrivateMcpToolSchemaRegistry.class);

    private final ApplicationContext applicationContext;

    /**
     * 保存按方法唯一键索引的注册描述。
     * 方法签名是内部调用与反射定位的稳定主键，能避免重载场景下仅凭方法名产生歧义。
     */
    private Map<String, PrivateMcpToolSchemaDescriptor> schemaDescriptorsByMethodKey = Map.of();

    /**
     * 保存按方法唯一键索引的 Method 对象。
     * 注册时直接缓存反射结果，避免调用阶段再次按字符串查找方法带来的重复解析成本。
     */
    private Map<String, Method> methodsByMethodKey = Map.of();

    public PrivateMcpToolSchemaRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 在全部单例 Bean 创建完成后执行私有工具扫描。
     * 选择该时机可以同时兼容 AOP 代理与普通组件，避免过早初始化导致目标类信息不完整。
     */
    @Override
    public void afterSingletonsInstantiated() {
        LinkedHashMap<String, PrivateMcpToolSchemaDescriptor> discoveredDescriptors = new LinkedHashMap<>();
        LinkedHashMap<String, Method> discoveredMethods = new LinkedHashMap<>();
        for (String beanName : this.applicationContext.getBeanDefinitionNames()) {
            Object bean = this.applicationContext.getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            ReflectionUtils.doWithMethods(
                    targetClass,
                    method -> registerToolMethod(discoveredDescriptors, discoveredMethods, beanName, targetClass, method),
                    method -> AnnotatedElementUtils.hasAnnotation(method, PrivateMcpTool.class)
            );
        }
        this.schemaDescriptorsByMethodKey = Collections.unmodifiableMap(discoveredDescriptors);
        this.methodsByMethodKey = Collections.unmodifiableMap(discoveredMethods);
    }

    /**
     * 返回全部私有方法的注册描述。
     * 供其他 Bean 一次性拿到当前注册快照，用于提示词拼装、权限校验或调试输出。
     *
     * @return 按方法唯一键索引的注册描述
     */
    public Map<String, PrivateMcpToolSchemaDescriptor> getAllMethodSchemas() {
        return this.schemaDescriptorsByMethodKey;
    }

    /**
     * 返回面向 LLM 规划阶段的精简方法描述。
     * 只暴露 methodKey、用途说明和参数 Schema，确保模型生成的调用计划与执行器消费协议一一对应。
     *
     * @return 按 methodKey 索引的精简方法描述
     */
    public Map<String, ToolPlanningSchema> getPlanningSchemas() {
        LinkedHashMap<String, ToolPlanningSchema> planningSchemas = new LinkedHashMap<>();
        for (Map.Entry<String, PrivateMcpToolSchemaDescriptor> entry : this.schemaDescriptorsByMethodKey.entrySet()) {
            PrivateMcpToolSchemaDescriptor descriptor = entry.getValue();
            planningSchemas.put(entry.getKey(), new ToolPlanningSchema(
                    descriptor.methodKey(),
                    descriptor.description(),
                    descriptor.inputSchema()
            ));
        }
        return Collections.unmodifiableMap(planningSchemas);
    }

    /**
     * 按方法唯一键查询单个私有方法的注册描述。
     * 通过 Optional 显式表达“未注册”分支，调用方可以根据业务场景决定是降级还是快速失败。
     *
     * @param methodKey 方法唯一键
     * @return 对应的注册描述
     */
    public Optional<PrivateMcpToolSchemaDescriptor> getMethodSchema(String methodKey) {
        return Optional.ofNullable(this.schemaDescriptorsByMethodKey.get(methodKey));
    }

    /**
     * 返回全部私有方法的有序注册描述列表。
     * 当调用方需要遍历输出而不是按唯一键索引时，可直接复用注册顺序避免二次转换。
     *
     * @return 有序注册描述列表
     */
    public List<PrivateMcpToolSchemaDescriptor> getOrderedMethodSchemas() {
        return List.copyOf(this.schemaDescriptorsByMethodKey.values());
    }

    /**
     * 按方法唯一键解析 Method 对象。
     * 让调用方能够基于注册结果直接拿到缓存好的 Method，而不是重新拼签名再做二次反射。
     *
     * @param methodKey 方法唯一键
     * @return 对应的 Method
     */
    public Optional<Method> getRegisteredMethod(String methodKey) {
        return Optional.ofNullable(this.methodsByMethodKey.get(methodKey));
    }

    /**
     * 通过方法唯一键解析对应的 Spring Bean。
     * 让后续反射调用方能够沿用注册时确认过的 beanName，避免自己重复推导目标实例。
     *
     * @param methodKey 方法唯一键
     * @return 对应的 Bean 实例
     */
    public Optional<Object> getRegisteredBean(String methodKey) {
        PrivateMcpToolSchemaDescriptor descriptor = this.schemaDescriptorsByMethodKey.get(methodKey);
        if (descriptor == null) {
            return Optional.empty();
        }
        return Optional.of(this.applicationContext.getBean(descriptor.beanName()));
    }

    /**
     * 注册单个私有工具方法的元数据。
     * 对重复方法唯一键直接失败，避免后注册的方法悄悄覆盖前者，导致反射调用拿到错误目标。
     *
     * @param discoveredDescriptors 已发现的注册描述集合
     * @param discoveredMethods 已发现的 Method 集合
     * @param beanName 当前 Spring Bean 名称
     * @param targetClass 目标类
     * @param method 目标方法
     */
    private void registerToolMethod(
            Map<String, PrivateMcpToolSchemaDescriptor> discoveredDescriptors,
            Map<String, Method> discoveredMethods,
            String beanName,
            Class<?> targetClass,
            Method method
    ) {
        PrivateMcpTool annotation = AnnotatedElementUtils.findMergedAnnotation(method, PrivateMcpTool.class);
        if (annotation == null) {
            return;
        }
        List<String> parameterTypeNames = resolveParameterTypeNames(method);
        String methodKey = buildMethodKey(targetClass, method.getName(), parameterTypeNames);
        PrivateMcpToolSchemaDescriptor.MethodSchema outputSchema = annotation.generateOutputSchema()
                ? PrivateMcpToolJsonSchemaGenerator.generateMethodOutputSchema(method)
                : null;
        PrivateMcpToolSchemaDescriptor descriptor = new PrivateMcpToolSchemaDescriptor(
                methodKey,
                beanName,
                method.getName(),
                targetClass.getName(),
                method.getName(),
                parameterTypeNames,
                annotation.description(),
                PrivateMcpToolJsonSchemaGenerator.generateMethodInputSchema(method),
                outputSchema
        );
        log.info("注册内部MCPTool: {}", GsonUtil.toJson(descriptor));
        PrivateMcpToolSchemaDescriptor previousDescriptor = discoveredDescriptors.putIfAbsent(methodKey, descriptor);
        if (previousDescriptor != null) {
            throw new IllegalStateException("Duplicate @PrivateMcpTool method key detected: " + methodKey);
        }
        Method previousMethod = discoveredMethods.putIfAbsent(methodKey, method);
        if (previousMethod != null) {
            throw new IllegalStateException("Duplicate cached method detected: " + methodKey);
        }
    }

    /**
     * 构建方法唯一键。
     * 使用声明类、方法名与参数类型共同确定签名，确保重载方法在注册表中可以被稳定区分。
     *
     * @param targetClass 目标类
     * @param methodName 方法名
     * @param parameterTypeNames 参数类型名列表
     * @return 方法唯一键
     */
    private String buildMethodKey(Class<?> targetClass, String methodName, List<String> parameterTypeNames) {
        return targetClass.getName() + "#" + methodName + "(" + String.join(",", parameterTypeNames) + ")";
    }

    /**
     * 提取方法参数类型列表。
     * 使用全限定名而不是简单类名，避免不同包下同名类型导致签名串冲突。
     *
     * @param method 目标方法
     * @return 参数类型名列表
     */
    private List<String> resolveParameterTypeNames(Method method) {
        List<String> parameterTypeNames = new ArrayList<>();
        for (Class<?> parameterType : method.getParameterTypes()) {
            parameterTypeNames.add(parameterType.getName());
        }
        return parameterTypeNames;
    }

    /**
     * 承载供 LLM 规划使用的最小方法描述。
     *
     * @param methodKey 方法唯一键
     * @param description 方法用途说明
     * @param parameters 参数 Schema
     */
    public record ToolPlanningSchema(
            String methodKey,
            String description,
            PrivateMcpToolSchemaDescriptor.MethodSchema parameters
    ) {
    }
}
