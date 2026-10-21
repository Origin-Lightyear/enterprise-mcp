package com.kevin.mcp.cloud.planning;

import com.kevin.mcp.cloud.employee.EmployeeLlmInvocationConfig;
import com.kevin.mcp.cloud.planning.prompt.PlanningPrompt;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证云端规划在校验失败后能把确定性错误回喂给模型自纠。
 *
 * @author Kevin
 * @date 2026-10-16
 */
class ExecutionPlanGeneratorRetryTest {

    /** 验证重复步骤名触发重试，第二次合法计划应直接返回。 */
    @Test
    void shouldRetryValidationFailureAndReturnRepairedPlan() throws IOException {
        RetryLlmClient llm = new RetryLlmClient(List.of(com.kevin.mcp.util.GsonUtil.toJson(invalidPlan()), com.kevin.mcp.util.GsonUtil.toJson(validPlan())));
        ExecutionPlanGenerator generator = new ExecutionPlanGenerator(
                new PlanningPrompt(), llm, new ExecutionPlanValidator());

        Map<String, Object> plan = generator.generate("查询采购订单", catalog(), config());

        assertThat(((List<?>) plan.get("steps")).getFirst()).asString().contains("purchase-order-query");
        assertThat(llm.prompts()).hasSize(2);
        assertThat(llm.prompts().get(1)).contains("Duplicate step name");
        assertThat(llm.prompts().get(1)).contains("完整重新生成");
    }

    /** 验证重试耗尽后必须暴露首个确定性校验原因，不能折叠成空错误。 */
    @Test
    void shouldExposeValidationReasonWhenRetryExhausted() throws IOException {
        RetryLlmClient llm = new RetryLlmClient(List.of(com.kevin.mcp.util.GsonUtil.toJson(invalidPlan()), com.kevin.mcp.util.GsonUtil.toJson(invalidPlan()), com.kevin.mcp.util.GsonUtil.toJson(invalidPlan())));
        ExecutionPlanGenerator generator = new ExecutionPlanGenerator(
                new PlanningPrompt(), llm, new ExecutionPlanValidator());

        assertThatThrownBy(() -> generator.generate("查询采购订单", catalog(), config()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate step name")
                .hasMessageContaining("3 attempts");
        assertThat(llm.prompts()).hasSize(3);
    }

    private static Map<String, Object> invalidPlan() {
        Map<String, Object> step = Map.of(
                "type", "call",
                "id", "page1",
                "capabilityId", "database.semantic-query",
                "capabilityVersion", 1,
                "args", Map.of("sourceKey", "finance", "question", "查询财务数据")
        );
        return Map.of("intent", "查询采购订单", "steps", List.of(step, step));
    }

    private static Map<String, Object> validPlan() {
        return Map.of(
                "intent", "查询采购订单",
                "steps", List.of(Map.of(
                        "type", "call",
                        "id", "purchase-order-query",
                        "capabilityId", "database.semantic-query",
                        "capabilityVersion", 1,
                        "args", Map.of("sourceKey", "finance", "question", "查询财务数据")
                ))
        );
    }

    private static String catalog() {
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
                "operationMode", "READ_ONLY",
                "batchMode", "SINGLE"
        );
        Map<String, Object> semanticSources = Map.of("finance", List.of("finance"));
        Map<String, Object> semanticModel = Map.of("sources", semanticSources);
        return GsonUtilStatic.toJson(Map.of("capabilities", List.of(capability), "semanticModel", semanticModel));
    }

    private static EmployeeLlmInvocationConfig config() {
        return new EmployeeLlmInvocationConfig("https://llm.test", "key", "test-model");
    }

    /**
     * 按预设响应模拟云端规划模型，并记录每轮用户提示便于断言纠错上下文。
     *
     * @author Kevin
     * @date 2026-10-16
     */
    private static final class RetryLlmClient extends CloudLlmClient {
        private final java.util.List<? extends String> responses;
        private final List<String> prompts = new java.util.ArrayList<>();

        private RetryLlmClient(List<String> responses) {
            super(new com.kevin.mcp.cloud.configuration.ControlPlanePlanningProperties());
            this.responses = responses;
        }

        private List<String> prompts() {
            return prompts;
        }

        @Override
        public String chat(EmployeeLlmInvocationConfig invocationConfig, String systemPrompt,
                           String userPrompt) throws IOException {
            prompts.add(userPrompt);
            if (prompts.size() > responses.size()) {
                throw new IOException("Unexpected extra LLM call");
            }
            return responses.get(prompts.size() - 1);
        }
    }

    private static final class GsonUtilStatic {
        private static String toJson(Object value) {
            return com.kevin.mcp.util.GsonUtil.toJson(value);
        }
    }
}
