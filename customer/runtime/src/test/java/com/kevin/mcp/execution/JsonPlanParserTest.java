package com.kevin.mcp.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证云端执行计划必须携带完整绑定字段。
 *
 * @author Kevin
 * @date 2026-09-17
 */
class JsonPlanParserTest {

    private final JsonPlanParser parser = new JsonPlanParser();

    /** 验证缺少计划编号时在协议入口直接拒绝。 */
    @Test
    void shouldRejectMissingPlanId() {
        assertThatThrownBy(() -> parser.parse(validPlan().replace("\"planId\":\"plan-1\",", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planId");
    }

    /** 验证缺少协议版本时在协议入口直接拒绝。 */
    @Test
    void shouldRejectMissingPlanContractVersion() {
        assertThatThrownBy(() -> parser.parse(validPlan().replace("\"planContractVersion\":1,", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planContractVersion");
    }

    /** 验证每个不可变绑定字段都必须存在。 */
    @Test
    void shouldRejectMissingImmutableBindingField() {
        String[] fields = {"tenantId", "instanceId", "runtimeVersion", "artifactDigest",
                "catalogDigest", "asOf", "consistencyMode"};
        for (String field : fields) {
            String fragment = "\"" + field + "\":\"" + fieldValue(field) + "\",";
            assertThatThrownBy(() -> parser.parse(validPlan().replace(fragment, "")))
                    .as("missing field %s", field)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(field);
        }
    }

    /** 验证上游数字字符串 employeeId 会被兼容解析为 Long。 */
    @Test
    void shouldAcceptEmployeeIdAsNumericString() {
        var plan = parser.parse(validPlan().replace("\"employeeId\":1001,", "\"employeeId\":\"1001\","));
        org.assertj.core.api.Assertions.assertThat(plan.employeeId()).isEqualTo(1001L);
    }

    /** 验证非数字 employeeId 在协议入口直接拒绝。 */
    @Test
    void shouldRejectNonNumericEmployeeId() {
        assertThatThrownBy(() -> parser.parse(validPlan().replace("\"employeeId\":1001,", "\"employeeId\":\"employee-1\",")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Long field is invalid");
    }

    private String validPlan() {
        return """
                {"planId":"plan-1","planContractVersion":1,"tenantId":"tenant-1",\
                "employeeId":1001,"instanceId":"instance-1","runtimeVersion":"1.0.0",\
                "artifactDigest":"artifact","catalogDigest":"catalog","asOf":"2026-09-17T00:00:00Z",\
                "consistencyMode":"BEST_EFFORT_CURRENT","intent":"查询",\
                "steps":[{"type":"call","id":"step-1","capabilityId":"capability-1",\
                "capabilityVersion":1,"args":{}}]}
                """;
    }

    private String fieldValue(String field) {
        return switch (field) {
            case "tenantId" -> "tenant-1";
            case "instanceId" -> "instance-1";
            case "runtimeVersion" -> "1.0.0";
            case "artifactDigest" -> "artifact";
            case "catalogDigest" -> "catalog";
            case "asOf" -> "2026-09-17T00:00:00Z";
            case "consistencyMode" -> "BEST_EFFORT_CURRENT";
            default -> throw new IllegalArgumentException("Unknown field: " + field);
        };
    }
}