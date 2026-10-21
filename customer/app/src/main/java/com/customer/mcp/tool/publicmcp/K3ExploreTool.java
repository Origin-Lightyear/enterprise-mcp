package com.customer.mcp.tool.publicmcp;

import com.customer.mcp.k3.catalog.K3Catalog;
import com.customer.mcp.k3.knowledge.KnowledgeEntry;
import com.customer.mcp.k3.knowledge.KnowledgeStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kevin.mcp.annotation.PublicMcpTool;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供已验证员工可直接调用的金蝶元数据探索工具。
 *
 * <p>源项目的表单目录与字段探查在这里拆为候选搜索和现场 QueryBusinessInfo。
 * 公开工具只返回结构信息，不返回业务行。Agent 必须区分候选来源、现场存在性和
 * 用户确认的语义；搜索无命中不能证明金蝶没有该表单。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Component
@ConditionalOnProperty(name = "enterprise.k3.enabled", havingValue = "true")
public class K3ExploreTool {

    private static final String USAGE_GUIDE = "verified 只说明当前账套中验证过表单存在；confidence 表示本次意图匹配程度。"
            + "静态目录候选须核对现场元数据；候选接近、参数不明确或都不匹配时应请求用户确认，不要循环猜测。";
    private final K3Catalog catalog;
    private final KnowledgeStore knowledge;

    /**
     * 注入现场目录和知识层。
     *
     * @param catalog 金蝶目录聚合器
     * @param knowledge 本地知识层
     */
    public K3ExploreTool(K3Catalog catalog, KnowledgeStore knowledge) {
        this.catalog = catalog;
        this.knowledge = knowledge;
    }

    /**
     * 搜索可能对应员工意图的表单，保留候选歧义供用户确认。
     *
     * <p>最多返回 20 个候选；目录外 FormId 仍可通过 describeForm 直接向当前账套验证。
     * 候选相近或无命中时不要继续猜测业务模型。</p>
     *
     * @param intentFragment 员工描述的业务概念
     * @param requestContext MCP 请求上下文，由安全切面校验
     * @return 经过排序的候选和取舍说明
     */
    @McpTool(name = "k3_search_forms", description = "搜索金蝶 FormId 候选。verified 仅表示现场验证过存在，不表示业务含义正确；confidence 是意图匹配程度，hint 是取舍提示。工具描述以金蝶固定工具开头的 MCP 是领域固定查询，候选 FormId 与其描述中的 FormId 一致时直接调用该工具，不要探测。候选接近时必须确认，不要按名称猜。",
            generateOutputSchema = true)
    @PublicMcpTool(capabilityId = "k3.search-forms", description = "搜索金蝶表单目录及已验证候选")
    public Map<String, Object> searchForms(
            @McpToolParam(description = "员工业务意图片段，如采购订单或科目余额", required = true) String intentFragment,
            McpSyncRequestContext requestContext) {
        if (intentFragment == null || intentFragment.isBlank() || intentFragment.length() > 200) {
            throw new IllegalArgumentException("K3 search intent must contain 1 to 200 characters");
        }
        var candidates = catalog.search(intentFragment);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usageGuide", USAGE_GUIDE);
        result.put("candidates", candidates.stream().limit(20).toList());
        result.put("outcome", candidates.isEmpty() ? "NO_CANDIDATES" : "CANDIDATES");
        return result;
    }

    /**
     * 读取金蝶实时元数据并只返回字段 Key 和名称，不泄露完整配置对象。
     *
     * <p>字段列表最多展示 500 项，达到上限时不是完整 Schema。
     * 字段真实存在也不能证明该表单符合用户原始业务意图。</p>
     *
     * @param formId 待验证的 FormId
     * @param requestContext MCP 请求上下文，由安全切面校验
     * @return 表单字段和取舍说明
     */
    @McpTool(name = "k3_describe_form", description = "从当前客户金蝶账套查询真实 FormId 和字段 Key。字段存在不代表该表单符合用户意图；若多个相似表单都存在，应向用户确认。",
            generateOutputSchema = true)
    @PublicMcpTool(capabilityId = "k3.describe-form", description = "查询金蝶表单现场字段元数据")
    public Map<String, Object> describeForm(
            @McpToolParam(description = "要验证的金蝶 FormId", required = true) String formId,
            McpSyncRequestContext requestContext) {
        JsonObject metadata = catalog.describe(formId);
        JsonObject result = metadata.getAsJsonObject("Result");
        JsonObject model = result != null && result.has("NeedReturnData")
                && result.get("NeedReturnData").isJsonObject()
                ? result.getAsJsonObject("NeedReturnData") : null;
        List<Map<String, String>> fields = new ArrayList<>();
        if (model != null && model.has("Entrys") && model.get("Entrys").isJsonArray()) {
            for (JsonElement entryElement : model.getAsJsonArray("Entrys")) {
                if (!entryElement.isJsonObject()) {
                    continue;
                }
                JsonObject entry = entryElement.getAsJsonObject();
                if (!entry.has("Fields") || !entry.get("Fields").isJsonArray()) {
                    continue;
                }
                JsonArray entryFields = entry.getAsJsonArray("Fields");
                for (JsonElement fieldElement : entryFields) {
                    if (fieldElement.isJsonObject() && fieldElement.getAsJsonObject().has("Key")) {
                        JsonObject field = fieldElement.getAsJsonObject();
                        fields.add(Map.of("key", field.get("Key").getAsString(),
                                "name", localizedName(field.get("Name"))));
                    }
                }
            }
        }
        return Map.of("formId", formId, "verified", true, "fields", fields.stream().limit(500).toList(),
                "usageGuide", USAGE_GUIDE);
    }

    /**
     * 根据已确认知识建议参数，不把未确认模板冒充必填参数。
     *
     * <p>仅返回当前账套最近的十条用户确认；期间可能过期，
     * 与本次用户范围不一致时必须再次澄清，不能静默复用。</p>
     *
     * @param formId 金蝶表单标识
     * @param requestContext MCP 请求上下文，由安全切面校验
     * @return 已确认参数提示
     */
    @McpTool(name = "k3_suggest_params", description = "给出当前账套已确认的金蝶参数建议。建议可能过期，账簿、期间等未明确时须通过 Elicitation 向用户确认。",
            generateOutputSchema = true)
    @PublicMcpTool(capabilityId = "k3.suggest-params", description = "读取当前账套确认过的查询参数建议")
    public Map<String, Object> suggestParams(
            @McpToolParam(description = "金蝶 FormId", required = true) String formId,
            McpSyncRequestContext requestContext) {
        if (formId == null || !formId.matches("[A-Za-z0-9_]{1,80}")) {
            throw new IllegalArgumentException("Invalid K3 formId");
        }
        List<Map<String, Object>> hints = knowledge.snapshot().stream()
                .filter(entry -> entry.key().contains(formId)
                        && entry.source() == KnowledgeEntry.Source.USER_CONFIRMATION)
                .sorted(Comparator.comparingLong(KnowledgeEntry::observedAt).reversed())
                .limit(10)
                .map(entry -> Map.<String, Object>of("layer", entry.layer().name(),
                        "key", entry.key(), "value", entry.value(), "expiresAt", entry.expiresAt()))
                .toList();
        return Map.of("formId", formId, "hints", hints, "usageGuide", USAGE_GUIDE);
    }

    private static String localizedName(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonObject() && item.getAsJsonObject().has("Key")
                        && item.getAsJsonObject().get("Key").getAsInt() == 2052
                        && item.getAsJsonObject().has("Value")) {
                    return item.getAsJsonObject().get("Value").getAsString();
                }
            }
        }
        return "";
    }
}
