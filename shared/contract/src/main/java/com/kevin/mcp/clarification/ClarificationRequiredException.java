package com.kevin.mcp.clarification;

/**
 * 中断当前只读计划并要求 MCP 客户端向用户发起澄清。
 *
 * @author Kevin
 * @date 2026-09-21
 */
public class ClarificationRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ClarificationRequest request;

    /**
     * 创建不包含业务数据结果的澄清异常。
     *
     * @param request 结构化澄清请求
     */
    public ClarificationRequiredException(ClarificationRequest request) {
        super("User clarification is required: " + (request == null ? "unknown" : request.id()));
        if (request == null) {
            throw new IllegalArgumentException("Clarification request is required");
        }
        this.request = request;
    }

    /**
     * 返回待展示和校验的澄清请求。
     *
     * @return 结构化澄清请求
     */
    public ClarificationRequest getRequest() {
        return request;
    }
}
