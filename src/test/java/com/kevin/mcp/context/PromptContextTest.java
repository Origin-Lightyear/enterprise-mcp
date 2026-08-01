package com.kevin.mcp.context;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 验证 LLM 提示词中的执行计划 Schema 契约。
 *
 * @author Kevin
 * @date 2026-08-01
 */
class PromptContextTest {

    /**
     * 验证执行计划 Schema 在保持合法 JSON 的同时移除格式空白。
     */
    @Test
    void shouldMinifyExecutionPlanSchema() {
        assertThat(PromptContext.OUTPUT_SCHEMA)
                .doesNotContain("\r")
                .doesNotContain("\n");
        assertThatCode(() -> JsonParser.parseString(PromptContext.OUTPUT_SCHEMA))
                .doesNotThrowAnyException();
    }
}
