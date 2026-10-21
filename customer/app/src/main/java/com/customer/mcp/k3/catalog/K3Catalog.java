package com.customer.mcp.k3.catalog;

import com.customer.mcp.k3.client.K3Gateway;
import com.customer.mcp.k3.knowledge.KnowledgeEntry;
import com.customer.mcp.k3.knowledge.KnowledgeStore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 汇合静态表单目录、客户元数据和确认知识供探索工具使用。
 *
 * <p>源项目的 form-catalog.json 是出厂提示而非客户账套的完整 Schema；
 * 目录里不存在的 FormId 也可能在客户现场真实存在。候选名称相似或接口返回数据，
 * 均不能证明业务语义正确。只有 QueryBusinessInfo 的成功状态证明表单存在，
 * 用户确认才证明该 FormId 匹配本次意图。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Component
public class K3Catalog {

    private static final long METADATA_TTL_MILLIS = 60_000;
    private final Map<String, JsonObject> staticForms = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, CachedMetadata> metadata = new ConcurrentHashMap<>();
    private final K3Gateway gateway;
    private final KnowledgeStore knowledge;

    /**
     * 加载出厂目录，并保留客户现场元数据的独立缓存。
     *
     * @param gateway 金蝶 SDK 网关
     * @param knowledge 本地知识层
     */
    public K3Catalog(K3Gateway gateway, KnowledgeStore knowledge) {
        this.gateway = gateway;
        this.knowledge = knowledge;
        try (InputStreamReader reader = new InputStreamReader(
                new ClassPathResource("k3/form-catalog.json").getInputStream(), StandardCharsets.UTF_8)) {
            JsonArray forms = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement item : forms) {
                JsonObject form = item.getAsJsonObject();
                staticForms.put(form.get("formId").getAsString(), form);
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("K3 form catalog loading failed", exception);
        }
    }

    /**
     * 搜索当前意图的候选表单并返回面向 Agent 的决策提示。
     *
     * <p>合并静态目录、现场验证、用户确认三个不同来源并分别标注；
     * confidence 是候选排序分数，不是对业务结果正确性的概率保证。</p>
     *
     * @param keyword 用户意图片段
     * @return 排序后的候选表单
     */
    public List<FormCandidate> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        List<FormCandidate> result = new ArrayList<>();
        Set<String> included = new HashSet<>();
        for (Map.Entry<String, JsonObject> entry : staticForms.entrySet()) {
            JsonObject form = entry.getValue();
            String id = entry.getKey();
            if (!matches(form, id, normalized)) {
                continue;
            }
            KnowledgeEntry confirmed = knowledge.get(KnowledgeEntry.Layer.ENTITY, "intent:" + normalized);
            KnowledgeEntry verified = knowledge.get(KnowledgeEntry.Layer.ENTITY, "form:" + id);
            boolean selected = confirmed != null && confirmed.source() == KnowledgeEntry.Source.USER_CONFIRMATION
                    && id.equalsIgnoreCase(confirmed.value());
            boolean present = verified != null || metadata.containsKey(id);
            // 当前账套“存在”与用户“确认含义”是两个不同证据，不得合并为同一种 verified。
            double confidence = selected ? 1.0 : present ? 0.65 : 0.25;
            String hint = selected ? "用户曾明确确认此表单匹配当前意图" : present
                    ? "已在当前账套验证表单存在，业务含义仍需核对" : "仅来自出厂目录，当前账套尚未验证";
            result.add(new FormCandidate(id, text(form, "name"), present, confidence, hint));
            included.add(id.toLowerCase(Locale.ROOT));
        }
        KnowledgeEntry confirmed = knowledge.get(KnowledgeEntry.Layer.ENTITY, "intent:" + normalized);
        if (confirmed != null && confirmed.source() == KnowledgeEntry.Source.USER_CONFIRMATION
                && !included.contains(confirmed.value().toLowerCase(Locale.ROOT))) {
            result.add(new FormCandidate(confirmed.value(), confirmed.value(), true, 1.0,
                    "用户曾明确确认此表单匹配当前意图"));
            included.add(confirmed.value().toLowerCase(Locale.ROOT));
        }
        for (KnowledgeEntry entry : knowledge.snapshot()) {
            if (entry.layer() != KnowledgeEntry.Layer.ENTITY || !entry.key().startsWith("form:")) {
                continue;
            }
            String id = entry.key().substring("form:".length());
            if (id.toLowerCase(Locale.ROOT).contains(normalized)
                    && included.add(id.toLowerCase(Locale.ROOT))) {
                result.add(new FormCandidate(id, id, true, 0.65, "已在当前账套验证表单存在，业务含义仍需核对"));
            }
        }
        result.sort(Comparator.comparingDouble(FormCandidate::confidence).reversed());
        // 同分候选保持加入顺序：静态目录为 LinkedHashMap，出厂排序优先于知识缓存补充项。
        return List.copyOf(result);
    }

    /**
     * 读取客户真实表单元数据；缓存只减少请求，不把出厂字段当成已验证字段。
     *
     * <p>源项目曾清理过 ResponseStatus.IsSuccess=false 的错误元数据缓存；
     * 这里同样只缓存明确成功的响应。60 秒内复用现场响应以减少反复探测，
     * 过期后仍需重新向当前账套查询。</p>
     *
     * @param formId 表单标识
     * @return 金蝶元数据 JSON
     */
    public JsonObject describe(String formId) {
        if (formId == null || !formId.matches("[A-Za-z0-9_]{1,80}")) {
            throw new IllegalArgumentException("Invalid K3 formId");
        }
        CachedMetadata cached = metadata.get(formId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.value().deepCopy();
        }
        JsonElement response = JsonParser.parseString(gateway.metadata(formId));
        if (!response.isJsonObject()) {
            throw new IllegalStateException("K3 metadata response is not an object");
        }
        JsonObject value = response.getAsJsonObject();
        JsonObject result = value.has("Result") && value.get("Result").isJsonObject()
                ? value.getAsJsonObject("Result") : null;
        JsonObject status = result != null && result.has("ResponseStatus")
                && result.get("ResponseStatus").isJsonObject()
                ? result.getAsJsonObject("ResponseStatus") : null;
        if (status == null || !status.has("IsSuccess") || !status.get("IsSuccess").getAsBoolean()) {
            throw new IllegalStateException("K3 form metadata is unavailable");
        }
        metadata.put(formId, new CachedMetadata(value.deepCopy(), now + METADATA_TTL_MILLIS));
        knowledge.observe(KnowledgeEntry.Layer.ENTITY, "form:" + formId, "present", 30L * 24 * 3600);
        return value;
    }

    /**
     * 检查表单是否只在出厂目录出现。
     *
     * @param formId 表单标识
     * @return 存在静态候选时返回 true
     */
    public boolean isStaticCandidate(String formId) {
        return staticForms.containsKey(formId);
    }

    private static boolean matches(JsonObject form, String id, String keyword) {
        String name = text(form, "name").toLowerCase(Locale.ROOT);
        if (id.toLowerCase(Locale.ROOT).contains(keyword)
                || (id.length() >= 4 && keyword.contains(id.toLowerCase(Locale.ROOT)))
                || name.contains(keyword) || (name.length() >= 2 && keyword.contains(name))
                || text(form, "desc").toLowerCase(Locale.ROOT).contains(keyword)) {
            return true;
        }
        if (form.has("alias") && form.get("alias").isJsonArray()) {
            for (JsonElement alias : form.getAsJsonArray("alias")) {
                String term = alias.getAsString().toLowerCase(Locale.ROOT);
                if (term.contains(keyword) || (term.length() >= 2 && keyword.contains(term))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String text(JsonObject form, String key) {
        return form.has(key) && !form.get(key).isJsonNull() ? form.get(key).getAsString() : "";
    }

    private record CachedMetadata(JsonObject value, long expiresAt) {
    }

    /**
     * 告诉 Agent 候选是否在现场验证过以及其业务匹配程度。
     *
     * @param formId 表单 ID
     * @param name 表单中文名
     * @param verified 当前账套已验证存在
     * @param confidence 当前意图匹配置信度
     * @param hint 取舍说明
     * @author Kevin
     * @date 2026-10-14
     */
    public record FormCandidate(String formId, String name, boolean verified,
                                double confidence, String hint) {
    }
}
