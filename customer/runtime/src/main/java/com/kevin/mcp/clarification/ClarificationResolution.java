package com.kevin.mcp.clarification;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * 承载 MCP 客户端对一次澄清请求的处理结果。
 *
 * @param action 用户接受、拒绝或取消
 * @param confirmation 可安全加入后续规划意图的确认内容
 * @param selectedOptionId 用户选择的稳定字段标识；自由文本回答时为空
 * @param freeTextDigest 自由文本回答摘要；不在审计中保存原文
 * @author Kevin
 * @date 2026-09-21
 */
public record ClarificationResolution(
        McpSchema.ElicitResult.Action action,
        String confirmation,
        String selectedOptionId,
        String freeTextDigest
) {

    /**
     * 判断用户是否接受并提交了有效答案。
     *
     * @return 接受时返回 true
     */
    public boolean accepted() {
        return McpSchema.ElicitResult.Action.ACCEPT.equals(action);
    }
}
