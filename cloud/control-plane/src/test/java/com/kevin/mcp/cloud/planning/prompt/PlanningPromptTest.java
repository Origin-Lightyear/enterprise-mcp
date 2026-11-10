package com.kevin.mcp.cloud.planning.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证规划提示词对数据源显示名称和稳定标识的契约。
 *
 * @author Kevin
 * @date 2026-10-27
 */
class PlanningPromptTest {

    /** 验证中文显示名称只用于匹配，计划必须继续输出稳定 sourceKey。 */
    @Test
    void shouldRequireStableSourceKeyWhenCatalogContainsChineseDisplayName() {
        String catalogJson = """
                {"capabilities":[],"semanticModel":{"sources":{"sales":{}}},
                 "semanticSourceDisplayNames":{"sales":"销售数据库"}}
                """;

        PlanningPrompt.Messages messages = new PlanningPrompt().build("查询销售数据库本月订单", catalogJson);

        assertThat(messages.systemMessage())
                .contains("semanticSourceDisplayNames")
                .contains("计划参数必须输出稳定 sourceKey")
                .contains("禁止把显示名称作为 sourceKey");
        assertThat(messages.userMessage()).contains("销售数据库").contains("sales");
    }
}
