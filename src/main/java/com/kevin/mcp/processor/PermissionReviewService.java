package com.kevin.mcp.processor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.registry.PrivateMcpToolSchemaDescriptor;
import com.kevin.mcp.registry.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.util.GsonUtil;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审查内部执行计划是否命中当前员工有效权限规则，并对允许结果生成脱敏指令。
 *
 * @author Kevin
 * @date 2026-07-29
 */
@Service
public class PermissionReviewService {

    private static final Type REVIEW_RESULT_TYPE = new TypeToken<PermissionReviewResult>() {
    }.getType();

    private final LLMProcessor llmProcessor;
    private final PrivateMcpToolSchemaRegistry schemaRegistry;

    /**
     * 注入 LLM 处理器和内部 Tool Schema 注册表。
     *
     * @param llmProcessor LLM 处理器
     * @param schemaRegistry 内部 Tool Schema 注册表
     */
    public PermissionReviewService(LLMProcessor llmProcessor, PrivateMcpToolSchemaRegistry schemaRegistry) {
        this.llmProcessor = llmProcessor;
        this.schemaRegistry = schemaRegistry;
    }

    /**
     * 审查执行计划，规则为空时直接允许，避免普通请求额外消耗 LLM。
     *
     * @param userMessage 员工原始消息
     * @param plan 执行计划
     * @param permissionConfig 当前员工有效权限快照
     * @return 审查结果
     * @throws IOException LLM 请求异常
     */
    public PermissionReviewResult review(String userMessage, JsonExecutionPlan plan, Map<String, Object> permissionConfig) throws IOException {
        if (!hasRules(permissionConfig)) {
            return PermissionReviewResult.allow();
        }
        String prompt = buildPrompt(userMessage, plan, permissionConfig);
        String rawResult = this.llmProcessor.chat(prompt);
        PermissionReviewResult result = GsonUtil.fromJson(extractJson(rawResult), REVIEW_RESULT_TYPE);
        if (result == null || result.uncertain()) {
            return new PermissionReviewResult("deny", "权限审查无法确认，已拒绝执行", List.of(), List.of(), true);
        }
        return result;
    }

    /**
     * 按脱敏指令过滤最终结果，默认保留字段结构并替换字段值。
     *
     * @param finalResult 原始结果
     * @param reviewResult 审查结果
     * @return 脱敏后的结果
     */
    public Object maskFinalResult(Object finalResult, PermissionReviewResult reviewResult) {
        if (reviewResult == null || reviewResult.maskInstructions().isEmpty() || finalResult == null) {
            return finalResult;
        }
        JsonElement root = JsonParser.parseString(GsonUtil.toJson(finalResult));
        for (PermissionReviewResult.MaskInstruction instruction : reviewResult.maskInstructions()) {
            maskField(root, instruction.fieldPath(), instruction.maskTemplate());
        }
        return GsonUtil.fromJson(root.toString(), Object.class);
    }

    private boolean hasRules(Map<String, Object> permissionConfig) {
        if (permissionConfig == null || permissionConfig.isEmpty()) {
            return false;
        }
        return isNonEmptyList(permissionConfig.get("denyRules")) || isNonEmptyList(permissionConfig.get("sensitiveRules"));
    }

    private boolean isNonEmptyList(Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    private String buildPrompt(String userMessage, JsonExecutionPlan plan, Map<String, Object> permissionConfig) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userMessage", userMessage);
        context.put("permissionConfig", permissionConfig);
        context.put("executionPlan", plan);
        context.put("toolSchemas", resolvePlanSchemas(plan));
        return """
                你是 MCPServer 权限审查器。请只基于输入的员工有效权限快照、执行计划和 Tool 输入/输出 Schema 做判断。
                规则：
                1. 禁止规则命中时 decision 必须为 deny，整次 MCP 请求拒绝。
                2. 敏感规则命中时 decision 为 allow，并在 maskInstructions 中给出字段脱敏指令。
                3. 如果无法确定是否命中禁止规则，uncertain=true 且 decision=deny。
                4. 不要输出 Markdown，不要解释，只输出 JSON。
                输出 JSON Schema：
                {
                  "decision": "allow|deny",
                  "denyReason": "拒绝原因，没有则为空字符串",
                  "matchedDenyRules": [],
                  "maskInstructions": [
                    {"methodKey":"内部方法键","fieldPath":"字段路径","maskTemplate":"MASK_ALL|MASK_PHONE|MASK_NAME|MASK_ADDRESS|RANGE|SUMMARY_ONLY","ruleId":"规则ID","ruleName":"规则名称"}
                  ],
                  "uncertain": false
                }
                输入：
                """ + GsonUtil.toJson(context);
    }

    private Map<String, PrivateMcpToolSchemaDescriptor> resolvePlanSchemas(JsonExecutionPlan plan) {
        Map<String, PrivateMcpToolSchemaDescriptor> schemas = new LinkedHashMap<>();
        for (JsonExecutionStep step : plan.steps()) {
            this.schemaRegistry.getMethodSchema(step.methodKey()).ifPresent(schema -> schemas.put(step.methodKey(), schema));
        }
        return schemas;
    }

    private String extractJson(String rawResult) {
        if (rawResult == null) {
            return "{}";
        }
        String text = rawResult.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private void maskField(JsonElement root, String fieldPath, String maskTemplate) {
        if (root == null || fieldPath == null || fieldPath.isBlank()) {
            return;
        }
        if (root.isJsonArray()) {
            root.getAsJsonArray().forEach(item -> maskField(item, fieldPath, maskTemplate));
            return;
        }
        if (!root.isJsonObject()) {
            return;
        }
        applyMask(root.getAsJsonObject(), fieldPath.split("\\."), 0, maskTemplate);
    }

    private void applyMask(JsonObject object, String[] fields, int index, String maskTemplate) {
        String fieldName = fields[index];
        if (!object.has(fieldName)) {
            return;
        }
        if (index == fields.length - 1) {
            object.addProperty(fieldName, maskValue(object.get(fieldName), maskTemplate));
            return;
        }
        JsonElement child = object.get(fieldName);
        if (child.isJsonObject()) {
            applyMask(child.getAsJsonObject(), fields, index + 1, maskTemplate);
        } else if (child.isJsonArray()) {
            child.getAsJsonArray().forEach(item -> {
                if (item.isJsonObject()) {
                    applyMask(item.getAsJsonObject(), fields, index + 1, maskTemplate);
                }
            });
        }
    }

    private String maskValue(JsonElement value, String maskTemplate) {
        String text = value == null || value.isJsonNull() ? "" : value.getAsString();
        if ("MASK_PHONE".equalsIgnoreCase(maskTemplate) && text.length() >= 7) {
            return text.substring(0, 3) + "****" + text.substring(text.length() - 4);
        }
        if ("MASK_NAME".equalsIgnoreCase(maskTemplate) && text.length() > 1) {
            return text.substring(0, 1) + "*";
        }
        if ("MASK_ADDRESS".equalsIgnoreCase(maskTemplate) && text.length() > 3) {
            return text.substring(0, 3) + "***";
        }
        return "***";
    }
}
