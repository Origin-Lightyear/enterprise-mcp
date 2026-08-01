package com.kevin.mcp.registry;

import com.kevin.mcp.annotation.PrivateMcpTool;
import com.kevin.mcp.annotation.PrivateMcpToolParam;
import com.kevin.mcp.util.GsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证私有 MCP Tool 规划 Schema 的输出契约。
 *
 * @author Kevin
 * @date 2026-08-01
 */
class PrivateMcpToolSchemaRegistryTest {

    /**
     * 验证规划描述包含返回值 Schema，并省略递归结构中的空字段。
     */
    @Test
    void shouldIncludeCompactOutputSchemaInPlanningSchemas() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SamplePrivateTool.class);
            context.registerBean(PrivateMcpToolSchemaRegistry.class);
            context.refresh();

            PrivateMcpToolSchemaRegistry registry = context.getBean(PrivateMcpToolSchemaRegistry.class);
            String planningSchemasJson = GsonUtil.toJson(registry.getPlanningSchemas());

            assertThat(planningSchemasJson)
                    .startsWith("[")
                    .containsOnlyOnce("\"methodKey\"")
                    .contains("\"outputSchema\"")
                    .contains("\"description\":\"示例返回对象\"")
                    .contains("\"properties\":{\"id\":")
                    .doesNotContain("\"name\":\"id\"")
                    .doesNotContain("\"description\":\"\"")
                    .doesNotContain("\"required\":[]")
                    .doesNotContain("\"properties\":[]")
                    .doesNotContain("\"enumValues\":[]");
        }
    }

    /**
     * 提供测试注册使用的私有 Tool。
     *
     * @author Kevin
     * @date 2026-08-01
     */
    static class SamplePrivateTool {

        /**
         * 根据 ID 返回示例对象。
         *
         * @param id 示例 ID
         * @return 示例对象
         */
        @PrivateMcpTool(description = "根据 ID 查询示例对象")
        public SampleResult getById(
                @PrivateMcpToolParam(description = "示例 ID", required = true) Long id
        ) {
            return new SampleResult(id);
        }
    }

    /**
     * 承载用于验证输出 Schema 的示例数据。
     *
     * @author Kevin
     * @date 2026-08-01
     */
    @PrivateMcpToolParam(description = "示例返回对象")
    static class SampleResult {

        /** 示例 ID。 */
        @PrivateMcpToolParam(description = "示例 ID")
        private final Long id;

        SampleResult(Long id) {
            this.id = id;
        }
    }
}
