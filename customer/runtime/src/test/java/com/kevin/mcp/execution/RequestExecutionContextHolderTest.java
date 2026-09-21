package com.kevin.mcp.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证请求执行上下文在线程复用前可靠清理。
 *
 * @author Kevin
 * @date 2026-09-17
 */
class RequestExecutionContextHolderTest {

    /** 验证正常关闭作用域后不再保留员工请求上下文。 */
    @Test
    void shouldClearContextWhenScopeCloses() {
        RequestExecutionContextHolder holder = new RequestExecutionContextHolder();
        RequestExecutionContext context = new RequestExecutionContext(null, null, "mdl-digest");

        try (RequestExecutionContextHolder.Scope ignored = holder.bind(context)) {
            assertThat(holder.requireContext()).isSameAs(context);
        }

        assertThatThrownBy(holder::requireContext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not bound");
    }

    /** 验证异常退出作用域后仍会清理请求上下文。 */
    @Test
    void shouldClearContextAfterFailure() {
        RequestExecutionContextHolder holder = new RequestExecutionContextHolder();
        RequestExecutionContext context = new RequestExecutionContext(null, null, "mdl-digest");

        assertThatThrownBy(() -> {
            try (RequestExecutionContextHolder.Scope ignored = holder.bind(context)) {
                throw new IllegalStateException("boom");
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("boom");

        assertThatThrownBy(holder::requireContext).isInstanceOf(IllegalStateException.class);
    }
}
