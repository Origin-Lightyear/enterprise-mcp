package com.kevin.mcp.processor;

import java.util.List;
import java.util.Map;

/**
 * 承载执行计划中的单个步骤定义。
 * 统一覆盖普通调用与 foreach 循环两种步骤类型，避免解析器与执行器维护两套分叉模型。
 *
 * @param type 步骤类型，支持 call 和 foreach
 * @param id 步骤唯一标识
 * @param methodKey type=call 时要调用的内部方法唯一键
 * @param args type=call 时的方法参数
 * @param saveAs 可选，保存当前步骤结果的别名
 * @param dependsOn 可选，声明当前步骤依赖的前置步骤或别名
 * @param itemsFrom type=foreach 时要遍历的集合来源
 * @param itemAs type=foreach 时当前循环元素别名
 * @param body type=foreach 时的循环体步骤列表
 */
public record JsonExecutionStep(
        String type,
        String id,
        String methodKey,
        Map<String, Object> args,
        String saveAs,
        List<String> dependsOn,
        String itemsFrom,
        String itemAs,
        List<JsonExecutionStep> body
) {

    /**
     * 归一化可选集合字段，避免调用方重复判空。
     *
     * @param type 步骤类型
     * @param id 步骤唯一标识
     * @param methodKey 方法唯一键
     * @param args 方法参数
     * @param saveAs 结果别名
     * @param dependsOn 前置依赖
     * @param itemsFrom 集合来源
     * @param itemAs 循环元素别名
     * @param body 循环体
     */
    public JsonExecutionStep {
        args = args == null ? Map.of() : Map.copyOf(args);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        body = body == null ? List.of() : List.copyOf(body);
    }

    /**
     * 判断当前步骤是否为普通调用。
     *
     * @return true 表示 call 步骤
     */
    public boolean isCall() {
        return "call".equalsIgnoreCase(this.type);
    }

    /**
     * 判断当前步骤是否为 foreach 循环。
     *
     * @return true 表示 foreach 步骤
     */
    public boolean isForeach() {
        return "foreach".equalsIgnoreCase(this.type);
    }
}
