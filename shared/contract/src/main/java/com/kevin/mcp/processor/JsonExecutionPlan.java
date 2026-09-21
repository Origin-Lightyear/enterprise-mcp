package com.kevin.mcp.processor;

import java.util.List;

/**
 * 承载一次 JSON 协议调用计划。
 * 同时覆盖“可执行计划”与“模型明确声明无法生成计划”两种返回形态。
 *
 * @param planId 计划唯一标识
 * @param planContractVersion 计划协议版本
 * @param tenantId 计划绑定的租户标识
 * @param employeeId 计划绑定的员工标识
 * @param instanceId 计划绑定的 Runtime 实例标识
 * @param runtimeVersion 计划绑定的 Runtime 制品版本
 * @param artifactDigest 计划绑定的 Runtime 制品摘要
 * @param catalogDigest 计划生成时使用的 Catalog 摘要
 * @param mdlDigest 计划生成时使用的 Current MDL 摘要；未配置 Wren 时为空
 * @param asOf 计划统一观察时点
 * @param consistencyMode 数据一致性模式
 * @param intent 原始用户意图
 * @param steps 按顺序声明的执行步骤；失败计划允许为空
 * @param error 失败原因；有值时表示当前计划不应继续执行
 * @author Kevin
 * @date 2026-09-09
 */
public record JsonExecutionPlan(
        String planId,
        Integer planContractVersion,
        String tenantId,
        String employeeId,
        String instanceId,
        String runtimeVersion,
        String artifactDigest,
        String catalogDigest,
        String mdlDigest,
        String asOf,
        String consistencyMode,
        String intent,
        List<JsonExecutionStep> steps,
        String error
) {

    /**
     * 归一化可选字段，避免调用方重复判空。
     *
     * @param planId 计划唯一标识
     * @param planContractVersion 计划协议版本
     * @param tenantId 计划绑定的租户标识
     * @param employeeId 计划绑定的员工标识
     * @param instanceId 计划绑定的 Runtime 实例标识
     * @param runtimeVersion 计划绑定的 Runtime 制品版本
     * @param artifactDigest 计划绑定的 Runtime 制品摘要
     * @param catalogDigest 计划生成时使用的 Catalog 摘要
     * @param mdlDigest 计划生成时使用的 Current MDL 摘要
     * @param asOf 计划统一观察时点
     * @param consistencyMode 数据一致性模式
     * @param intent 原始用户意图
     * @param steps 执行步骤
     * @param error 失败原因
     */
    public JsonExecutionPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * 判断当前计划是否为失败结果。
     *
     * @return true 表示模型已明确声明无法生成可执行计划
     */
    public boolean hasError() {
        return error != null && !error.isBlank();
    }
}
