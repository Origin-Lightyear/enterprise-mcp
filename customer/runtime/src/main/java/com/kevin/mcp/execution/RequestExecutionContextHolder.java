package com.kevin.mcp.execution;

import org.springframework.stereotype.Component;

/**
 * 在同步计划执行线程内传递请求级安全上下文。
 *
 * <p>当前 MCP Tool 和计划执行器均为同步调用。每次绑定都必须通过 try-with-resources 关闭，
 * 防止线程复用时把员工密钥或权限决策泄漏到后续请求。</p>
 *
 * @author Kevin
 * @date 2026-09-17
 */
@Component
public class RequestExecutionContextHolder {

    private final ThreadLocal<RequestExecutionContext> contextHolder = new ThreadLocal<>();

    /**
     * 绑定当前请求上下文并返回自动清理句柄。
     *
     * @param context 当前请求上下文
     * @return 关闭时清理 ThreadLocal 的句柄
     */
    public Scope bind(RequestExecutionContext context) {
        if (context == null || contextHolder.get() != null) {
            throw new IllegalStateException("Request execution context is invalid or already bound");
        }
        contextHolder.set(context);
        return contextHolder::remove;
    }

    /**
     * 获取当前请求上下文。
     *
     * @return 已绑定的请求上下文
     * @throws IllegalStateException 当前线程没有执行上下文
     */
    public RequestExecutionContext requireContext() {
        RequestExecutionContext context = contextHolder.get();
        if (context == null) {
            throw new IllegalStateException("Request execution context is not bound");
        }
        return context;
    }

    /**
     * 定义请求上下文的自动清理句柄。
     *
     * @author Kevin
     * @date 2026-09-17
     */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        /** 清理当前线程绑定的请求上下文。 */
        @Override
        void close();
    }
}
