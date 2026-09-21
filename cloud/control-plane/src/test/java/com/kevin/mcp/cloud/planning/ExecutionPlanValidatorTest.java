package com.kevin.mcp.cloud.planning;

import com.kevin.mcp.util.GsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证云端计划只能选择 Catalog 已发布的 Wren 数据源。
 *
 * @author Kevin
 * @date 2026-09-18
 */
class ExecutionPlanValidatorTest {

    private final ExecutionPlanValidator validator = new ExecutionPlanValidator();

    /** 验证 Current MDL 中的 sourceKey 可以通过确定性计划校验。 */
    @Test
    void shouldAcceptCurrentWrenSource() {
        validator.validate(plan("finance"), "查询财务数据", catalog());
    }

    /** 验证 Current MDL 中不存在的 sourceKey 会在计划下发前被拒绝。 */
    @Test
    void shouldRejectUnknownWrenSource() {
        assertThatThrownBy(() -> validator.validate(plan("unknown"), "查询财务数据", catalog()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sourceKey is not present in Current MDL");
    }

    private Map<String, Object> plan(String sourceKey) {
        return Map.of(
                "intent", "查询财务数据",
                "steps", List.of(Map.of(
                        "type", "call",
                        "id", "finance-query",
                        "capabilityId", "database.semantic-query",
                        "capabilityVersion", 1,
                        "args", Map.of("sourceKey", sourceKey, "question", "查询财务数据")
                ))
        );
    }

    private String catalog() {
        Map<String, Object> stringSchema = Map.of("type", "string");
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("sourceKey", "question"),
                "properties", Map.of("sourceKey", stringSchema, "question", stringSchema)
        );
        Map<String, Object> capability = Map.of(
                "capabilityId", "database.semantic-query",
                "capabilityVersion", 1,
                "parameters", parameters,
                "executorType", "WREN",
                "batchMode", "SINGLE",
                "operationMode", "READ_ONLY"
        );
        return GsonUtil.toJson(Map.of(
                "capabilities", List.of(capability),
                "semanticModel", Map.of("sources", Map.of("finance", Map.of()))
        ));
    }
}
