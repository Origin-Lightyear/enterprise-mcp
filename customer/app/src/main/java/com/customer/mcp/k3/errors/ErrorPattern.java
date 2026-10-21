package com.customer.mcp.k3.errors;

/**
 * 描述一条金蝶错误到修正建议的映射。
 *
 * @param id 模式编号
 * @param match 用于包含匹配的错误关键词
 * @param reason 业务失败原因
 * @param suggestion Agent 可执行的修正建议
 * @param nextActionTool 建议下一步调用的工具名，可为空
 * @param nextActionArgsHint 建议工具的参数提示，可为空
 * @author Kevin
 * @date 2026-10-16
 */
public record ErrorPattern(String id, String match, String reason, String suggestion,
                           String nextActionTool, String nextActionArgsHint) {
}
