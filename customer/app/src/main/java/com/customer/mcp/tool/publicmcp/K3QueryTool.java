package com.customer.mcp.tool.publicmcp;

import com.customer.mcp.k3.catalog.K3Catalog;
import com.customer.mcp.k3.client.K3Gateway;
import com.customer.mcp.k3.knowledge.KnowledgeEntry;
import com.customer.mcp.k3.catalog.K3FieldLocalizer;
import com.customer.mcp.k3.knowledge.KnowledgeStore;
import com.customer.mcp.k3.errors.ErrorPattern;
import com.customer.mcp.k3.errors.ErrorPatternCatalog;
import com.kevin.mcp.execution.K3BusinessException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import com.kevin.mcp.annotation.PublicMcpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 供 Agent 直接调用查询金蝶单据和报表，经统一安全管线审查。
 *
 * <p>只承接源项目 {@code CoreTools.queryBills} 与 {@code ReportTools.queryReport} 的通用只读通道，
 * 不等价于源项目的字段纠错、领域查询或写入工作流。金蝶业务响应成功只能证明 API 接受了参数，
 * 不能证明 FormId、账簿、期间就是用户想要的数据模型。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Component
@ConditionalOnProperty(name = "enterprise.k3.enabled", havingValue = "true")
public class K3QueryTool {

    /** 失败参数记忆有效期；超时后允许 Agent 携带修正参数重新验证。 */
    private static final long FAILURE_MEMORY_TTL_MS = 10L * 60 * 1000;

    private final K3Gateway gateway;
    private final K3Catalog catalog;
    /** 现场元数据列头翻译器；通用查询结果同样展示用户可读列名。 */
    private final K3FieldLocalizer fieldLocalizer;
    private final KnowledgeStore knowledge;
    private final ErrorPatternCatalog errorPatterns;
    /** 记录最近被金蝶明确拒绝的参数签名；同参快速失败能打断 Agent 盲猜循环。 */
    private final ConcurrentHashMap<String, FailedCall> recentFailures = new ConcurrentHashMap<>();

    /**
     * 注入金蝶查询依赖。
     *
     * @param gateway 金蝶 SDK 网关
     * @param catalog 现场表单目录
     * @param fieldLocalizer 现场元数据列头翻译器
     * @param knowledge 客户本地知识
     * @param errorPatterns 金蝶错误模式目录
     */
    public K3QueryTool(K3Gateway gateway, K3Catalog catalog, KnowledgeStore knowledge,
                       ErrorPatternCatalog errorPatterns, K3FieldLocalizer fieldLocalizer) {
        this.gateway = gateway;
        this.catalog = catalog;
        this.fieldLocalizer = fieldLocalizer;
        this.knowledge = knowledge;
        this.errorPatterns = errorPatterns;
    }

    /**
     * 查询单据或基础资料，并限定分页避免一次读取过多业务数据。
     *
     * <p>源项目对 ExecuteBillQuery 的约束：FieldKeys 应先用现场元数据核对；
     * FilterString 为金蝶表达式而非 SQL，不支持 GETDATE/CONVERT/DATEADD 等函数。
     * K3 分页查询要求单据有主键或显式排序；无主键表单（例如余额表）必须传 OrderString，
     * 因此这里保留可验证的排序入口并在缺省时使用主键降级。
     * 返回行满页只表示可能还有下一页，不应盲目翻页拉全量。某些“字段不存在”错误被包在
     * {@code data[0][0].Result.ResponseStatus.Errors} 中，必须先排除行内错误再学习字段有效。</p>
     *
     * @param formId 金蝶表单 ID
     * @param fieldKeys 已在当前账套元数据验证的逗号分隔字段 Key
     * @param filterString 金蝶原生过滤表达式
     * @param order 金蝶排序表达式，例如 FID desc
     * @param startRow 起始行
     * @param limit 最大返回行数
     * @return 结构化查询结果
     * @throws IllegalStateException 金蝶在行内返回业务失败状态
     */
    @McpTool(name = "k3_query_data", description = "按当前账套真实 FormId 和字段查询金蝶单据或基础资料。FieldKeys 先用 k3_describe_form 核对；FilterString 是金蝶表达式而非 SQL，不支持 GETDATE/CONVERT/DATEADD。无主键表单分页必须传 order，且 order 必须使用本次 FieldKeys 中已包含的字段，如 FAccountID.FNumber asc；部分汇总表单没有 FID，禁止假设 FID 存在；order 为空时按首个 FieldKey 升序降级。满页不等于全量已查完；FormId 或字段不确定时先用 k3_search_forms 确认，禁止靠空结果反复猜测。",
            generateOutputSchema = true)
    @PublicMcpTool(capabilityId = "k3.query-data", description = "查询金蝶单据或基础资料业务数据")
    public Map<String, Object> queryData(
            @McpToolParam(description = "客户金蝶账套的真实表单 ID", required = true) String formId,
            @McpToolParam(description = "逗号分隔的真实字段 Key", required = true) String fieldKeys,
            @McpToolParam(description = "金蝶过滤表达式，可为空", required = false) String filterString,
            @McpToolParam(description = "金蝶排序表达式，无主键表单分页必传，必须来自本次 FieldKeys，例如 FAccountID.FNumber asc", required = false) String order,
            @McpToolParam(description = "分页起点，从 0 开始", required = false) Integer startRow,
            @McpToolParam(description = "单页行数，最大 100", required = false) Integer limit,
            McpSyncRequestContext requestContext) {

        validateFormId(formId);
        rejectRepeatedFailure(formId, fieldKeys, filterString, order, startRow, limit);
        if (fieldKeys == null || !fieldKeys.matches("[A-Za-z0-9_.,]{1,2000}")) {
            throw new IllegalArgumentException("K3 fieldKeys are required and must be field identifiers");
        }
        int row = startRow == null ? 0 : startRow;
        int size = limit == null ? 20 : limit;
        if (row < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("K3 query pagination is invalid");
        }
        // GL_Balance 这类余额/汇总表单没有默认主键；K3 分页必须有 OrderString，否则直接业务失败。
        // 部分汇总表单（如 GL_Balance）没有 FID；缺省排序必须来自本次已传入的字段，不能假设主键存在。
        String effectiveOrder = order == null || order.isBlank()
                ? fieldKeys.split(",")[0].trim() + " asc" : order.trim();
        String raw = gateway.query(formId, fieldKeys, filterString == null ? "" : filterString,
                effectiveOrder, row, size);
        JsonElement parsed = JsonParser.parseString(raw);
        JsonArray rows = parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        if (rows == null) {
            throw new IllegalStateException("K3 query response is not a row array");
        }
        // ExecuteBillQuery 可能以“正常数组 + 行内失败状态”返回字段不存在等错误。抛错时透传金蝶描述。
        // 未排除该状态前写入 form/field 正向知识，会让错误字段在后续会话中持续被推荐。
        rejectEmbeddedQueryErrors(rows, formId, fieldKeys, filterString, effectiveOrder, row, size);
        knowledge.observe(KnowledgeEntry.Layer.ENTITY, "form:" + formId, "present", 30L * 24 * 3600);
        for (String field : fieldKeys.split(",")) {
            knowledge.observe(KnowledgeEntry.Layer.ENTITY, "field:" + formId + ':' + field,
                    "valid", 30L * 24 * 3600);
        }
        // Gson JsonElement 不能直接放进 MCP 返回值：Jackson 按 JavaBean 反射
        // 会调用 getAsBigDecimal 等 getter，在数组长度不为 1 时抛出误导性异常。
        List<Object> safeRows = new ArrayList<>();
        for (JsonElement rowElement : rows) {
            safeRows.add(toPlainObject(rowElement));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", safeRows.size());
        result.put("hasMore", safeRows.size() == size);
        result.put("fieldKeys", List.of(fieldKeys.split(",")));
        result.put("fieldNames", fieldLocalizer.fieldNames(formId, fieldKeys));
        result.put("data", safeRows);
        return result;
    }

    /**
     * 查询金蝶系统报表，保持内层 Model 参数结构由报表契约决定。
     *
     * <p>源项目实测：科目余额表是 {@code GL_RPT_AccountBalance}，总账账龄分析表是
     * {@code GL_AgingSchedule}（没有 RPT_ 前缀）；其他类似名称的报表仍需逐张在 BOS/ApiDoc 核对。
     * FieldKeys 必须是真实报表列 Key，乱猜可能触发 totalWidth 渲染错误。
     * {@code FBALANCELEVEL=0} 时应配 {@code FSHOWFULLNAME=true}，否则可能触发 PadLeft(-N)。
     * 账簿在 {@code Model.FACCTBOOKID.FNumber}，期间由 FSTARTYEAR/FSTARTPERIOD 与
     * FENDYEAR/FENDPERIOD 指定；零行通常表示该账簿/期间没有余额数据，不表示应自动换模型。</p>
     *
     * @param formId 报表 ID
     * @param dataJson 金蝶 GetSysReportData 的参数对象 JSON
     * @return 报表原始结构化结果
     */
    @McpTool(name = "k3_query_report", description = "通过 GetSysReportData 查询金蝶报表。科目余额表 GL_RPT_AccountBalance、总账账龄分析 GL_AgingSchedule（无 RPT_）；其他报表须先用 k3_search_forms 搜索候选再确认。FieldKeys 必须是真实列 Key；分页用 StartRow/Limit；账簿用 Model.FACCTBOOKID.FNumber，期间用 FSTARTYEAR/FSTARTPERIOD/FENDYEAR/FENDPERIOD。FBALANCELEVEL=0 时配 FSHOWFULLNAME=true。零行不等于选错模型，也不证明选对模型；关键参数不明确时先向用户确认。",
            generateOutputSchema = true)
    @PublicMcpTool(capabilityId = "k3.query-report", description = "查询金蝶报表业务数据")
    public Map<String, Object> queryReport(
            @McpToolParam(description = "已确认的金蝶报表 FormId", required = true) String formId,
            @McpToolParam(description = "GetSysReportData 的结构化参数 JSON，含 Model、FieldKeys 和分页", required = true)
            String dataJson,
            McpSyncRequestContext requestContext) {

        validateFormId(formId);
        rejectRepeatedFailure(formId, dataJson, null, null, null, null);
        if (dataJson == null || dataJson.length() > 20_000) {
            throw new IllegalArgumentException("K3 report parameters are missing or too large");
        }
        JsonObject data = JsonParser.parseString(dataJson).getAsJsonObject();
        if (!data.has("Limit") || data.get("Limit").getAsInt() < 1 || data.get("Limit").getAsInt() > 100) {
            throw new IllegalArgumentException("K3 report Limit must be between 1 and 100");
        }
        JsonElement parsed = JsonParser.parseString(gateway.report(formId, data.toString()));
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException("K3 report response is not an object");
        }
        JsonObject response = parsed.getAsJsonObject();
        JsonObject result = response.has("Result") && response.get("Result").isJsonObject()
                ? response.getAsJsonObject("Result") : response;
        JsonObject status = result.has("ResponseStatus") && result.get("ResponseStatus").isJsonObject()
                ? result.getAsJsonObject("ResponseStatus") : null;
        if (status != null && status.has("IsSuccess") && !status.get("IsSuccess").getAsBoolean()) {
            recordFailure(formId, dataJson, null, null, null, null, status);
            throw new K3BusinessException(toBusinessExceptionAdvice(status));
        }
        // 成功后清除同表单失败记忆，允许 Agent 立即换参数继续。
        recentFailures.values().removeIf(entry -> entry.formId().equals(formId));
        // 只有金蝶明确返回业务成功标志时才学习表单存在；空行也不证明意图正确。
        if (status != null && status.has("IsSuccess") && status.get("IsSuccess").getAsBoolean()) {
            knowledge.observe(KnowledgeEntry.Layer.ENTITY, "form:" + formId, "present", 30L * 24 * 3600);
            if (result.has("Rows") && result.get("Rows").isJsonArray()
                    && !result.getAsJsonArray("Rows").isEmpty()) {
                observeReportDimensions(formId, data);
            }
        }
        return Map.of("data", toPlainObject(response));
    }

    private void observeReportDimensions(String formId, JsonObject data) {
        // 仅当报表明确成功且 Rows 非空时记录客观存在信号；绝不升级为用户意图映射。
        if (!data.has("Model") || !data.get("Model").isJsonObject()) {
            return;
        }
        JsonObject model = data.getAsJsonObject("Model");
        if (model.has("FACCTBOOKID") && model.get("FACCTBOOKID").isJsonObject()) {
            JsonObject book = model.getAsJsonObject("FACCTBOOKID");
            if (book.has("FNumber") && book.get("FNumber").isJsonPrimitive()) {
                String number = book.get("FNumber").getAsString();
                if (number.matches("[A-Za-z0-9_-]{1,40}")) {
                    knowledge.observe(KnowledgeEntry.Layer.TOPOLOGY,
                            "book:" + formId + ':' + number, "rows-present", 7L * 24 * 3600);
                }
            }
        }
        try {
            int startYear = model.get("FSTARTYEAR").getAsInt();
            int startMonth = model.get("FSTARTPERIOD").getAsInt();
            int endYear = model.get("FENDYEAR").getAsInt();
            int endMonth = model.get("FENDPERIOD").getAsInt();
            if (startYear >= 1900 && endYear <= 2200 && startMonth >= 1 && startMonth <= 12
                    && endMonth >= 1 && endMonth <= 12
                    && startYear * 12 + startMonth <= endYear * 12 + endMonth) {
                String start = "%04d-%02d".formatted(startYear, startMonth);
                String end = "%04d-%02d".formatted(endYear, endMonth);
                knowledge.observe(KnowledgeEntry.Layer.FRESHNESS,
                        "period:" + formId + ':' + (start.equals(end) ? start : start + ".." + end),
                        "rows-present", 24L * 3600);
            }
        } catch (RuntimeException exception) {
            // 非标准报表不提供上述期间字段，不影响成功的报表查询。
        }
    }

    /**
     * 拒绝金蝶藏在 ExecuteBillQuery 行内的业务错误，防止错误字段被正向学习。
     *
     * @param rows 金蝶返回的二维查询结果
     */
    private void rejectEmbeddedQueryErrors(JsonArray rows, String formId, String fieldKeys,
                                            String filterString, String effectiveOrder,
                                            int startRow, int limit) {
        for (JsonElement row : rows) {
            if (!row.isJsonArray() || row.getAsJsonArray().isEmpty()) {
                continue;
            }
            JsonElement firstCell = row.getAsJsonArray().get(0);
            if (!firstCell.isJsonObject()) {
                continue;
            }
            JsonObject cell = firstCell.getAsJsonObject();
            if (!cell.has("Result") || !cell.get("Result").isJsonObject()) {
                continue;
            }
            JsonObject result = cell.getAsJsonObject("Result");
            if (!result.has("ResponseStatus") || !result.get("ResponseStatus").isJsonObject()) {
                continue;
            }
            JsonObject status = result.getAsJsonObject("ResponseStatus");
            if (status.has("IsSuccess") && !status.get("IsSuccess").getAsBoolean()) {
                recordFailure(formId, fieldKeys, filterString, effectiveOrder, startRow, limit, status);
                throw new K3BusinessException(
                        toBusinessExceptionAdvice(status));
            }
        }
        recentFailures.values().removeIf(entry -> entry.formId().equals(formId));
    }

    /**
     * 提取金蝶行内业务错误描述，限制长度防止异常与日志被超大响应撑爆。
     *
     * @param status 金蝶ResponseStatus对象
     * @return 有界错误描述；无法解析时返回空串
     */
    private static String describeResponseStatusErrors(JsonObject status) {
        if (!status.has("Errors") || !status.get("Errors").isJsonArray()) {
            return "unknown embedded response status";
        }
        List<String> errors = new ArrayList<>();
        for (JsonElement element : status.getAsJsonArray("Errors")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject error = element.getAsJsonObject();
            String message = error.has("Message") && error.get("Message").isJsonPrimitive()
                    ? error.get("Message").getAsString() : "";
            String field = error.has("FieldName") && error.get("FieldName").isJsonPrimitive()
                    ? error.get("FieldName").getAsString() : "";
            String description = error.has("Description") && error.get("Description").isJsonPrimitive()
                    ? error.get("Description").getAsString() : "";
            String text = List.of(field, message, description).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.joining(": "));
            if (!text.isBlank() && !errors.contains(text)) {
                errors.add(text);
            }
            if (errors.size() >= 5) {
                break;
            }
        }
        String joined = String.join("; ", errors);
        return joined.length() <= 1000 ? joined : joined.substring(0, 1000) + "...";
    }

    /**
     * 在 TTL 内直接拒绝刚刚被金蝶明确拒绝的相同参数，强制 Agent 改参数或先探索。
     *
     * @param signatureParts 参与失败签名的参数
     */
    private void rejectRepeatedFailure(Object... signatureParts) {
        String signature = signature(signatureParts);
        FailedCall failed = recentFailures.get(signature);
        if (failed != null && System.currentTimeMillis() - failed.at() < FAILURE_MEMORY_TTL_MS) {
            throw new K3BusinessException("该参数组合刚被金蝶拒绝: " + failed.advice()
                    + "。请修正参数或先用探索工具确认，不要原样重试。");
        }
        recentFailures.values().removeIf(entry -> System.currentTimeMillis() - entry.at()
                >= FAILURE_MEMORY_TTL_MS);
    }

    /**
     * 记录金蝶明确业务失败的参数签名和有界修正建议。
     *
     * @param formId 当前表单 ID，用于成功后按表单清除失败记忆
     * @param signatureParts 参与签名的参数；最后一个必须是金蝶ResponseStatus
     */
    private void recordFailure(String formId, Object... signatureParts) {
        if (signatureParts.length == 0
                || !(signatureParts[signatureParts.length - 1] instanceof JsonObject status)) {
            throw new IllegalArgumentException("K3 failure status is required");
        }
        // 只保留少量最近失败，避免长驻进程积累；建议文本已在错误匹配阶段做有界截断。
        if (recentFailures.size() > 128) {
            recentFailures.clear();
        }
        recentFailures.put(formId + '|' + signature(signatureParts), new FailedCall(formId,
                System.currentTimeMillis(), toBusinessExceptionAdvice(status)));
    }

    /**
     * 规范化失败参数签名。
     *
     * @param signatureParts 参与签名的参数
     * @return 逗号分隔的参数签名
     */
    private static String signature(Object... signatureParts) {
        return Arrays.stream(signatureParts)
                .map(value -> value == null ? "" : String.valueOf(value))
                .collect(Collectors.joining("|"));
    }

    /**
     * 把 Gson JsonElement 转成纯 Java 集合，避免 MCP 序列化器误调 Gson getter。
     *
     * @param element 金蝶返回的 JSON 元素
     * @return 可被 Jackson 安全序列化的纯 Java 对象
     */
    private static Object toPlainObject(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), toPlainObject(entry.getValue()));
            }
            return map;
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(toPlainObject(item));
            }
            return list;
        }
        return element.getAsString();
    }

    /** 保留最近失败调用的表单、参数签名和建议，仅用于打断重复盲猜。 */
    private record FailedCall(String formId, long at, String advice) {
    }

    private String toBusinessExceptionAdvice(JsonObject status) {
        String rawError = describeResponseStatusErrors(status);
        ErrorPattern matched = errorPatterns.match(rawError);
        if (matched != null) {
            // 结构性失败只记模式哈希，不写业务数据或用户意图。
            knowledge.observe(KnowledgeEntry.Layer.ENTITY,
                    "failure:" + matched.id(), "known-error", 24L * 3600);
        }
        return matched == null ? rawError
                : rawError + " | 原因: " + matched.reason()
                + " | 建议: " + matched.suggestion()
                + (matched.nextActionTool() == null ? "" : " | 下一步: " + matched.nextActionTool());
    }

    private void validateFormId(String formId) {
        if (formId == null || !formId.matches("[A-Za-z0-9_]{1,80}")) {
            throw new IllegalArgumentException("Invalid K3 formId");
        }
        if (!catalog.isStaticCandidate(formId)
                && knowledge.get(KnowledgeEntry.Layer.ENTITY, "form:" + formId) == null) {
            throw new IllegalArgumentException("K3 formId is not in the catalog or verified knowledge");
        }
    }
}
