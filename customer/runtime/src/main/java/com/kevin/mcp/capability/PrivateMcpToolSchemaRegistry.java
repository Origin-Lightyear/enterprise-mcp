package com.kevin.mcp.capability;

import com.kevin.mcp.annotation.PrivateMcpTool;
import com.kevin.mcp.annotation.LocalCapability;
import com.kevin.mcp.registry.PrivateMcpToolJsonSchemaGenerator;
import com.kevin.mcp.registry.PrivateMcpToolSchemaDescriptor;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 注册并缓存私有 MCP 方法的元数据与 Schema 描述。
 * 在 Spring 单例初始化完成后统一扫描 {@link PrivateMcpTool}，避免业务方按需反射带来的重复开销与结果不一致问题。
 *
 * @author Kevin
 * @date 2026-07-21
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
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

    /** 保存稳定能力编号到内部方法唯一键的映射，供本地执行计划脱离 Java 方法名。 */
    private Map<String, String> methodKeysByCapabilityId = Map.of();

    /** 保存能力的版本、读写模式和数据域，供本地执行前校验。 */
    private Map<String, LocalCapabilityDescriptor> capabilitiesById = Map.of();

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
        LinkedHashMap<String, String> discoveredCapabilityMethods = new LinkedHashMap<>();
        LinkedHashMap<String, LocalCapabilityDescriptor> discoveredCapabilities = new LinkedHashMap<>();
        for (String beanName : this.applicationContext.getBeanDefinitionNames()) {
            Object bean = this.applicationContext.getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            ReflectionUtils.doWithMethods(
                    targetClass,
                    method -> registerToolMethod(discoveredDescriptors, discoveredMethods, discoveredCapabilityMethods, discoveredCapabilities, beanName, targetClass, method),
                    method -> AnnotatedElementUtils.hasAnnotation(method, PrivateMcpTool.class)
            );
        }
        log.info("注册内部MCPTool 数量: {} 个", discoveredDescriptors.size());
        this.schemaDescriptorsByMethodKey = Collections.unmodifiableMap(discoveredDescriptors);
        this.methodsByMethodKey = Collections.unmodifiableMap(discoveredMethods);
        this.methodKeysByCapabilityId = Collections.unmodifiableMap(discoveredCapabilityMethods);
        this.capabilitiesById = Collections.unmodifiableMap(discoveredCapabilities);
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
     * 同时暴露输入和输出 Schema，让模型能够基于真实返回字段构造跨步骤引用；空描述和空集合会在转换时省略，以控制提示词长度。
     *
     * @return 按注册顺序排列的精简方法描述
     */
    public List<ToolPlanningSchema> getPlanningSchemas() {
        List<ToolPlanningSchema> planningSchemas = new ArrayList<>();
        for (PrivateMcpToolSchemaDescriptor descriptor : this.schemaDescriptorsByMethodKey.values()) {
            String capabilityId = this.methodKeysByCapabilityId.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(descriptor.methodKey()))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            Integer capabilityVersion = capabilityId == null ? null : this.capabilitiesById.get(capabilityId).version();
            planningSchemas.add(new ToolPlanningSchema(
                    descriptor.methodKey(),
                    descriptor.description() == null || descriptor.description().isBlank() ? null : descriptor.description(),
                    compactSchema(descriptor.inputSchema()),
                    compactSchema(descriptor.outputSchema()),
                    capabilityId,
                    capabilityVersion
            ));
        }
        return List.copyOf(planningSchemas);
    }

    /**
     * 返回不包含 Java 方法键的云端规划 Schema。
     * 云端只需要稳定能力编号和输入输出契约，避免客户代码定位信息进入规划目录。
     *
     * @return 云端规划 Schema
     */
    public List<CloudPlanningSchema> getCloudPlanningSchemas() {
        List<CloudPlanningSchema> planningSchemas = new ArrayList<>();
        for (LocalCapabilityDescriptor capability : this.capabilitiesById.values()) {
            PrivateMcpToolSchemaDescriptor descriptor = this.schemaDescriptorsByMethodKey.get(capability.methodKey());
            planningSchemas.add(new CloudPlanningSchema(
                    capability.capabilityId(), capability.version(),
                    descriptor == null || descriptor.description().isBlank() ? null : descriptor.description(),
                    descriptor == null ? null : compactSchema(descriptor.inputSchema()),
                    descriptor == null ? null : compactSchema(descriptor.outputSchema()),
                    capability.mode().name(), capability.dataDomain()
            ));
        }
        return List.copyOf(planningSchemas);
    }

    /**
     * 将完整 Schema 转换为面向规划阶段的紧凑结构。
     * 保留 {@code false} 等具有约束语义的值，仅把空字符串、空集合和原始 null 转换为 null，交由 Gson 省略对应字段以减少 token 消耗。
     *
     * @param schema 完整方法 Schema
     * @return 紧凑 Schema；原始 Schema 为空时返回 null
     */
    private CompactMethodSchema compactSchema(PrivateMcpToolSchemaDescriptor.MethodSchema schema) {
        if (schema == null) {
            return null;
        }
        Map<String, CompactMethodSchema> properties = null;
        if (!schema.properties().isEmpty()) {
            LinkedHashMap<String, CompactMethodSchema> compactProperties = new LinkedHashMap<>();
            for (PrivateMcpToolSchemaDescriptor.SchemaProperty property : schema.properties()) {
                compactProperties.put(property.name(), compactSchema(property.schema()));
            }
            properties = Collections.unmodifiableMap(compactProperties);
        }
        return new CompactMethodSchema(
                schema.type(),
                schema.description() == null || schema.description().isBlank() ? null : schema.description(),
                schema.additionalProperties(),
                schema.required().isEmpty() ? null : schema.required(),
                properties,
                compactSchema(schema.items()),
                schema.enumValues().isEmpty() ? null : schema.enumValues()
        );
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
     * 根据稳定能力编号解析本地反射方法。
     *
     * @param capabilityId 能力编号
     * @return 已注册的方法；不存在时返回空
     */
    public Optional<Method> getRegisteredMethodByCapabilityId(String capabilityId) {
        String methodKey = this.methodKeysByCapabilityId.get(capabilityId);
        return methodKey == null ? Optional.empty() : this.getRegisteredMethod(methodKey);
    }

    /**
     * 根据稳定能力编号解析本地 Spring Bean。
     *
     * @param capabilityId 能力编号
     * @return 已注册的 Bean；不存在时返回空
     */
    public Optional<Object> getRegisteredBeanByCapabilityId(String capabilityId) {
        String methodKey = this.methodKeysByCapabilityId.get(capabilityId);
        return methodKey == null ? Optional.empty() : this.getRegisteredBean(methodKey);
    }

    /**
     * 返回能力编号与本地方法键的只读映射。
     *
     * @return 能力映射快照
     */
    public Map<String, String> getCapabilityMethodKeys() {
        return this.methodKeysByCapabilityId;
    }

    /**
     * 查询稳定能力的完整本地绑定描述。
     *
     * @param capabilityId 能力编号
     * @return 能力描述；不存在时返回空
     */
    public Optional<LocalCapabilityDescriptor> getCapability(String capabilityId) {
        return Optional.ofNullable(this.capabilitiesById.get(capabilityId));
    }

    /** 返回按注册顺序排列的本地能力描述，供构建产物校验和运维展示使用。 */
    public List<LocalCapabilityDescriptor> getCapabilityDescriptors() {
        return List.copyOf(this.capabilitiesById.values());
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
            Map<String, String> discoveredCapabilityMethods,
            Map<String, LocalCapabilityDescriptor> discoveredCapabilities,
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

        PrivateMcpToolSchemaDescriptor previousDescriptor = discoveredDescriptors.putIfAbsent(methodKey, descriptor);
        if (previousDescriptor != null) {
            throw new IllegalStateException("Duplicate @PrivateMcpTool method key detected: " + methodKey);
        }
        Method previousMethod = discoveredMethods.putIfAbsent(methodKey, method);
        if (previousMethod != null) {
            throw new IllegalStateException("Duplicate cached method detected: " + methodKey);
        }
        LocalCapability capability = AnnotatedElementUtils.findMergedAnnotation(method, LocalCapability.class);
        if (capability != null) {
            if (capability.id().isBlank()) {
                throw new IllegalStateException("Local capability id must not be blank: " + methodKey);
            }
            String previousCapability = discoveredCapabilityMethods.putIfAbsent(capability.id(), methodKey);
            if (previousCapability != null) {
                throw new IllegalStateException("Duplicate local capability id detected: " + capability.id());
            }
            discoveredCapabilities.put(capability.id(), new LocalCapabilityDescriptor(
                    capability.id(),
                    capability.version(),
                    methodKey,
                    capability.mode(),
                    capability.dataDomain()
            ));
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
     * 承载供 LLM 规划使用的方法描述。
     *
     * @param methodKey 方法唯一键
     * @param description 方法用途说明
     * @param parameters 参数 Schema
     * @param outputSchema 返回值 Schema
     */
    public record ToolPlanningSchema(
            String methodKey,
            String description,
            CompactMethodSchema parameters,
            CompactMethodSchema outputSchema,
            String capabilityId,
            Integer capabilityVersion
    ) {
    }

    /**
     * 承载脱离客户 Java 实现细节的云端规划契约。
     *
     * @param capabilityId 稳定能力编号
     * @param capabilityVersion 能力版本
     * @param description 能力描述
     * @param parameters 输入 Schema
     * @param outputSchema 输出 Schema
     * @param mode 读写模式
     * @param dataDomain 数据域
     */
    public record CloudPlanningSchema(
            String capabilityId,
            int capabilityVersion,
            String description,
            CompactMethodSchema parameters,
            CompactMethodSchema outputSchema,
            String mode,
            String dataDomain
    ) {
    }

    /**
     * 承载省略空值后的规划阶段 Schema 节点。
     * 独立于完整注册 Schema，避免 token 优化破坏权限审查阶段依赖的非空集合约定。
     *
     * @param type JSON Schema 类型
     * @param description 节点描述
     * @param additionalProperties 是否允许额外字段
     * @param required 必填字段
     * @param properties 按属性名索引的对象属性
     * @param items 数组元素 Schema
     * @param enumValues 枚举值
     */
    public record CompactMethodSchema(
            String type,
            String description,
            Boolean additionalProperties,
            List<String> required,
            Map<String, CompactMethodSchema> properties,
            CompactMethodSchema items,
            List<String> enumValues
    ) {
    }
}
