package com.kevin.mcp.execution;

/**
 * 金蝶 API 返回的业务级失败，消息中已包含原因和修正建议。
 *
 * <p>与通用运行时异常不同，该异常表示金蝶明确拒绝了当前参数而非系统故障。
 * CommonInternalTool 必须把其中消息透传给 Agent，而不是折叠为笼统失败提示。</p>
 *
 * @author Kevin
 * @date 2026-10-16
 */
public class K3BusinessException extends RuntimeException {

    /**
     * 构造携带金蝶错误描述和修正建议的业务异常。
     *
     * @param message 金蝶原始错误与修正建议的合并文本
     */
    public K3BusinessException(String message) {
        super(message);
    }
}
