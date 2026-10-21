package com.kevin.mcp.authorization;

import com.kevin.mcp.agent.model.EmployeeLlmInvocationConfig;
import com.kevin.mcp.annotation.BatchMode;
import com.kevin.mcp.annotation.ExecutorType;
import com.kevin.mcp.annotation.OperationMode;
import com.kevin.mcp.capability.LocalCapabilityDescriptor;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.execution.SemanticModelSnapshot;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionStep;
import com.kevin.mcp.registry.PrivateMcpToolSchemaDescriptor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证一次权限审查和最终脱敏的失败关闭语义。
 *
 * @author Kevin
 * @date 2026-09-17
 */
class PermissionReviewServiceTest {

    private final EmployeeLlmClient llmClient = mock(EmployeeLlmClient.class);
    private final PermissionReviewService service = new PermissionReviewService(
            llmClient, mock(PrivateMcpToolSchemaRegistry.class));

    /** 验证没有有效权限规则时不消耗员工模型调用。 */
    @Test
    void shouldSkipEmployeeLlmWhenPermissionRulesAreEmpty() throws IOException {
        PermissionReviewResult result = service.review("查询销售额", plan(), Map.of(), null,
                SemanticModelSnapshot.empty());

        assertThat(result.decision()).isEqualTo("allow");
        verify(llmClient, never()).chat(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
    }

    /** 验证员工模型不可用时权限审查失败关闭。 */
    @Test
    void shouldDenyWhenEmployeeLlmFails() throws IOException {
        EmployeeLlmInvocationConfig config = new EmployeeLlmInvocationConfig("https://example.test", "secret", "model");
        when(llmClient.chat(org.mockito.ArgumentMatchers.eq(config), anyString(), anyString()))
                .thenThrow(new IOException("network"));

        PermissionReviewResult result = service.review("查询采购成本", plan(),
                Map.of("denyRules", List.of(Map.of("id", "r1", "content", "禁止查看采购成本"))),
                config, SemanticModelSnapshot.empty());

        assertThat(result.decision()).isEqualTo("deny");
        assertThat(result.uncertain()).isTrue();
        assertThat(result.userMessage()).isEqualTo("权限校验未完成，请稍后重试");
    }

    /** 验证 Wren 派生列按照 MDL 源字段继承脱敏要求。 */
    @Test
    void shouldMaskWrenDerivedColumnBySourceField() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("store", "华东一店");
        row.put("average_cost", 128.50);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", List.of(row));
        result.put("columnLineage", Map.of(
                "store", List.of("Store.name"),
                "average_cost", List.of("Product.purchase_cost")
        ));
        PermissionReviewResult review = new PermissionReviewResult("allow", "", "", List.of(),
                List.of(new PermissionReviewResult.MaskInstruction(
                        "database.semantic-query", List.of(), List.of("Product.purchase_cost"),
                        "MASK_ALL", "r2", "采购成本脱敏")), false);

        Object masked = service.maskFinalResult(result, review);

        assertThat(masked).isInstanceOf(Map.class);
        Map<?, ?> maskedMap = (Map<?, ?>) masked;
        List<?> rows = (List<?>) maskedMap.get("rows");
        Map<?, ?> maskedRow = (Map<?, ?>) rows.getFirst();
        assertThat(maskedRow.get("store")).isEqualTo("华东一店");
        assertThat(maskedRow.get("average_cost")).isEqualTo("****");
    }

    /** 验证模型返回不存在的 MDL 字段时不会把脱敏零命中误当成功。 */
    @Test
    void shouldDenyWhenMaskInstructionReferencesUnknownMdlField() throws IOException {
        EmployeeLlmInvocationConfig config = new EmployeeLlmInvocationConfig("https://example.test", "secret", "model");
        when(llmClient.chat(org.mockito.ArgumentMatchers.eq(config), anyString(), anyString())).thenReturn("""
                {"decision":"allow","userMessage":"","internalReason":"","matchedRuleIds":[],
                 "maskInstructions":[{"capabilityId":"database.semantic-query","fieldPaths":[],
                 "sourceFields":["Employee.unknown"],"maskTemplate":"MASK_PHONE","ruleId":"r1","ruleName":"手机号"}],
                 "uncertain":false}
                """);
        JsonExecutionStep step = new JsonExecutionStep("call", "s1", null,
                Map.of("question", "查询员工手机号"), null, List.of(), null, null, List.of(),
                "database.semantic-query", 1);
        JsonExecutionPlan plan = new JsonExecutionPlan("p1", 1, "t1", 1L, "i1", "1.0.0",
                "artifact", "catalog", "", "2026-09-17T00:00:00Z", "BEST_EFFORT_CURRENT",
                "查询员工手机号", List.of(step), null);
        SemanticModelSnapshot mdl = new SemanticModelSnapshot("digest", Map.of("models", List.of(
                Map.of("name", "Employee", "columns", List.of(Map.of("name", "mobile"))))));

        PermissionReviewResult result = service.review("查询员工手机号", plan,
                Map.of("sensitiveRules", List.of(Map.of("id", "r1", "content", "手机号脱敏"))), config, mdl);

        assertThat(result.decision()).isEqualTo("deny");
        assertThat(result.uncertain()).isTrue();
    }

    /** 验证多数据源 MDL 使用 sourceKey 隔离同名模型字段。 */
    @Test
    void shouldAllowSourceQualifiedMdlField() throws IOException {
        EmployeeLlmInvocationConfig config = new EmployeeLlmInvocationConfig("https://example.test", "secret", "model");
        when(llmClient.chat(org.mockito.ArgumentMatchers.eq(config), anyString(), anyString())).thenReturn("""
                {"decision":"allow","userMessage":"","internalReason":"","matchedRuleIds":[],
                 "maskInstructions":[{"capabilityId":"database.semantic-query","fieldPaths":[],
                 "sourceFields":["sales:Employee.mobile"],"maskTemplate":"MASK_PHONE","ruleId":"r1","ruleName":"手机号"}],
                 "uncertain":false}
                """);
        JsonExecutionStep step = new JsonExecutionStep("call", "s1", null,
                Map.of("sourceKey", "sales", "question", "查询员工手机号"), null, List.of(), null, null,
                List.of(), "database.semantic-query", 1);
        JsonExecutionPlan plan = new JsonExecutionPlan("p1", 1, "t1", 1L, "i1", "1.0.0",
                "artifact", "catalog", "", "2026-09-17T00:00:00Z", "BEST_EFFORT_CURRENT",
                "查询员工手机号", List.of(step), null);
        Map<String, Object> employeeManifest = Map.of("models", List.of(
                Map.of("name", "Employee", "columns", List.of(Map.of("name", "mobile")))));
        SemanticModelSnapshot mdl = new SemanticModelSnapshot("digest", Map.of("sources", Map.of(
                "sales", Map.of("mdlDigest", "sales-digest", "manifest", employeeManifest),
                "finance", Map.of("mdlDigest", "finance-digest", "manifest", employeeManifest)
        )));

        PermissionReviewResult result = service.review("查询员工手机号", plan,
                Map.of("sensitiveRules", List.of(Map.of("id", "r1", "content", "手机号脱敏"))), config, mdl);

        assertThat(result.decision()).isEqualTo("allow");
        assertThat(result.uncertain()).isFalse();
    }

    /** 验证 API 脱敏字段路径不在输出 Schema 中时失败关闭。 */
    @Test
    void shouldDenyWhenApiMaskPathIsOutsideOutputSchema() throws IOException {
        EmployeeLlmInvocationConfig config = new EmployeeLlmInvocationConfig("https://example.test", "secret", "model");
        when(llmClient.chat(org.mockito.ArgumentMatchers.eq(config), anyString(), anyString())).thenReturn("""
                {"decision":"allow","userMessage":"","internalReason":"","matchedRuleIds":[],
                 "maskInstructions":[{"capabilityId":"cap.employee.query","fieldPaths":["mobile"],
                 "sourceFields":[],"maskTemplate":"MASK_PHONE","ruleId":"r1","ruleName":"手机号"}],
                 "uncertain":false}
                """);
        PrivateMcpToolSchemaRegistry registry = mock(PrivateMcpToolSchemaRegistry.class);
        LocalCapabilityDescriptor capability = new LocalCapabilityDescriptor("cap.employee.query", 1,
                "employee.query", OperationMode.READ_ONLY, ExecutorType.JAVA, BatchMode.SINGLE, "EMPLOYEE");
        when(registry.getCapability("cap.employee.query")).thenReturn(Optional.of(capability));
        when(registry.getMethodSchema("employee.query")).thenReturn(Optional.of(apiSchema("name")));
        PermissionReviewService reviewService = new PermissionReviewService(llmClient, registry);

        PermissionReviewResult result = reviewService.review("查询员工手机号", apiPlan(),
                Map.of("sensitiveRules", List.of(Map.of("id", "r1", "content", "手机号脱敏"))),
                config, SemanticModelSnapshot.empty());

        assertThat(result.decision()).isEqualTo("deny");
        assertThat(result.uncertain()).isTrue();
    }

    /** 验证 API 脱敏字段路径与输出 Schema 一致时允许执行。 */
    @Test
    void shouldAllowApiMaskPathDeclaredByOutputSchema() throws IOException {
        EmployeeLlmInvocationConfig config = new EmployeeLlmInvocationConfig("https://example.test", "secret", "model");
        when(llmClient.chat(org.mockito.ArgumentMatchers.eq(config), anyString(), anyString())).thenReturn("""
                {"decision":"allow","userMessage":"","internalReason":"","matchedRuleIds":[],
                 "maskInstructions":[{"capabilityId":"cap.employee.query","fieldPaths":["mobile"],
                 "sourceFields":[],"maskTemplate":"MASK_PHONE","ruleId":"r1","ruleName":"手机号"}],
                 "uncertain":false}
                """);
        PrivateMcpToolSchemaRegistry registry = mock(PrivateMcpToolSchemaRegistry.class);
        LocalCapabilityDescriptor capability = new LocalCapabilityDescriptor("cap.employee.query", 1,
                "employee.query", OperationMode.READ_ONLY, ExecutorType.JAVA, BatchMode.SINGLE, "EMPLOYEE");
        when(registry.getCapability("cap.employee.query")).thenReturn(Optional.of(capability));
        when(registry.getMethodSchema("employee.query")).thenReturn(Optional.of(apiSchema("mobile")));
        PermissionReviewService reviewService = new PermissionReviewService(llmClient, registry);

        PermissionReviewResult result = reviewService.review("查询员工手机号", apiPlan(),
                Map.of("sensitiveRules", List.of(Map.of("id", "r1", "content", "手机号脱敏"))),
                config, SemanticModelSnapshot.empty());

        assertThat(result.decision()).isEqualTo("allow");
        assertThat(result.uncertain()).isFalse();
    }

    private JsonExecutionPlan apiPlan() {
        JsonExecutionStep step = new JsonExecutionStep("call", "s1", null, Map.of(), null,
                List.of(), null, null, List.of(), "cap.employee.query", 1);
        return new JsonExecutionPlan("p1", 1, "t1", 1L, "i1", "1.0.0",
                "artifact", "catalog", "", "2026-09-17T00:00:00Z", "BEST_EFFORT_CURRENT",
                "查询员工手机号", List.of(step), null);
    }

    private PrivateMcpToolSchemaDescriptor apiSchema(String outputField) {
        PrivateMcpToolSchemaDescriptor.MethodSchema stringSchema = new PrivateMcpToolSchemaDescriptor.MethodSchema(
                "string", "", null, List.of(), List.of(), null, List.of());
        PrivateMcpToolSchemaDescriptor.MethodSchema outputSchema = new PrivateMcpToolSchemaDescriptor.MethodSchema(
                "object", "", false, List.of(),
                List.of(new PrivateMcpToolSchemaDescriptor.SchemaProperty(outputField, stringSchema)),
                null, List.of());
        return new PrivateMcpToolSchemaDescriptor("employee.query", "employeeTool", "query",
                "EmployeeTool", "query", List.of(), "查询员工", null, outputSchema);
    }

    private JsonExecutionPlan plan() {
        return new JsonExecutionPlan("p1", 1, "t1", 1L, "i1", "1.0.0",
                "artifact", "catalog", "", "2026-09-17T00:00:00Z", "BEST_EFFORT_CURRENT",
                "查询", List.of(), "无法生成计划");
    }
}
