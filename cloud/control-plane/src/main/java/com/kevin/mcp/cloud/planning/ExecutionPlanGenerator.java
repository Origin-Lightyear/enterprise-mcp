package com.kevin.mcp.cloud.planning;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.cloud.planning.prompt.PlanningPrompt;
import com.kevin.mcp.cloud.employee.EmployeeLlmInvocationConfig;
import com.kevin.mcp.util.GsonUtil;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * 在云端组合 Schema、Prompt 和 LLM，并产出通过结构校验的执行计划。
 *
 * @author Kevin
 * @date 2026-09-10
 */
@Service
public class ExecutionPlanGenerator {

    private static final Type PLAN_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final PlanningPrompt planningPrompt;
    private final CloudLlmClient llmClient;
    private final ExecutionPlanValidator planValidator;

    /**
     * 注入云端提示词、LLM 客户端和计划校验器。
     *
     * @param planningPrompt 云端规划提示词
     * @param llmClient 云端 LLM 客户端
     * @param planValidator 执行计划校验器
     */
    public ExecutionPlanGenerator(PlanningPrompt planningPrompt, CloudLlmClient llmClient,
                                  ExecutionPlanValidator planValidator) {
        this.planningPrompt = planningPrompt;
        this.llmClient = llmClient;
        this.planValidator = planValidator;
    }

    /**
     * 使用当前客户 Catalog 生成严格 JSON 计划。
     *
     * @param intent 用户原始意图
     * @param catalogJson ACTIVE Catalog JSON
     * @param invocationConfig 当前员工的请求级 LLM 调用配置
     * @return 已通过白名单和协议校验的计划
     * @throws IOException LLM 请求失败
     */
    public Map<String, Object> generate(String intent, String catalogJson,
                                        EmployeeLlmInvocationConfig invocationConfig) throws IOException {
        PlanningPrompt.Messages messages = planningPrompt.build(intent, catalogJson);
        String rawPlan = llmClient.chat(invocationConfig, messages.systemMessage(), messages.userMessage());
        Map<String, Object> plan = parseStrictObject(rawPlan);
        planValidator.validate(plan, intent, catalogJson);
        return plan;
    }

    private Map<String, Object> parseStrictObject(String rawPlan) {
        if (rawPlan == null || rawPlan.isBlank()) {
            throw new IllegalStateException("LLM returned an empty execution plan");
        }
        String text = rawPlan.trim();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            throw new IllegalStateException("LLM must return only one JSON object");
        }
        try {
            JsonElement json = JsonParser.parseString(text);
            if (!json.isJsonObject()) {
                throw new IllegalStateException("LLM execution plan must be a JSON object");
            }
            Map<String, Object> plan = GsonUtil.fromJson(json.toString(), PLAN_TYPE);
            if (plan == null) {
                throw new IllegalStateException("LLM returned an empty execution plan");
            }
            return plan;
        } catch (JsonSyntaxException exception) {
            throw new IllegalStateException("LLM returned invalid execution plan JSON", exception);
        }
    }
}
