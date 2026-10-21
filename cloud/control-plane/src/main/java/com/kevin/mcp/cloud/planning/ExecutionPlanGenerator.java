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

    /** 同一次业务请求内允许模型根据校验错误重试的最大次数。 */
    private static final int MAX_GENERATION_ATTEMPTS = 3;

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
        return generate(intent, catalogJson, "", invocationConfig);
    }

    /**
     * 使用当前 Catalog 和客户上报的结构知识生成计划。
     *
     * @param intent 员工意图
     * @param catalogJson 当前能力目录
     * @param knowledgeJson 当前结构知识摘要内容
     * @param invocationConfig 员工请求级模型配置
     * @return 已校验执行计划
     * @throws IOException 模型调用失败
     */
    public Map<String, Object> generate(String intent, String catalogJson, String knowledgeJson,
                                        EmployeeLlmInvocationConfig invocationConfig) throws IOException {
        PlanningPrompt.Messages messages = planningPrompt.build(intent, catalogJson, knowledgeJson);
        StringBuilder userPrompt = new StringBuilder(messages.userMessage());
        IllegalStateException lastFailure = null;
        // 模型偶发输出重复步骤名或协议外字段；把确定性校验错误回喂给模型，可在同一次业务请求内完成自纠。
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            String rawPlan = llmClient.chat(
                    invocationConfig, messages.systemMessage(), userPrompt.toString());
            try {
                Map<String, Object> plan = parseStrictObject(rawPlan);
                planValidator.validate(plan, intent, catalogJson);
                return plan;
            } catch (IllegalStateException failure) {
                lastFailure = failure;
                userPrompt.append("\n\n上一次输出未通过确定性校验，错误：").append(failure.getMessage())
                        .append("。请完整重新生成一个执行计划 JSON 对象，必须修复上述错误；不得解释，也不得返回部分片段。");
            }
        }
        throw new IllegalStateException("LLM execution plan failed validation after "
                + MAX_GENERATION_ATTEMPTS + " attempts: " + lastFailure.getMessage(), lastFailure);
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
