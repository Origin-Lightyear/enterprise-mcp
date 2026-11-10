package com.customer.mcp.tool.publicmcp.k3;

import com.customer.mcp.k3.catalog.K3FieldLocalizer;
import com.customer.mcp.k3.client.K3Gateway;
import com.customer.mcp.k3.errors.ErrorPattern;
import com.customer.mcp.k3.errors.ErrorPatternCatalog;
import com.customer.mcp.k3.knowledge.KnowledgeEntry;
import com.customer.mcp.k3.knowledge.KnowledgeStore;
import com.kevin.mcp.annotation.PublicMcpTool;
import com.kevin.mcp.execution.K3BusinessException;
import com.kevin.mcp.export.ExportedQueryResult;
import com.kevin.mcp.export.LargeResultExportService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 固定查询会计核算账簿（BD_AccountBook），是 {@code k3_query_data} 的领域化只读薄封装。
 *
 * <p>账簿是金蝶总账模块的基础资料入口，凭证汇总、科目余额等固定工具都需要先
 * 确定账簿编码。该工具支持按编码或名称模糊查询，也支持无条件全量搜索，
 * 让 Agent 在一轮调用内拿到可用账簿清单，而不是像通用工具链那样反复
 * 分页猜测。可选附带各账簿的调整期间列表，调整期间 FormId 属于现场
 * 待验证信息：查询失败时主结果不失败，只返回有界警告引导 Agent 用
 * {@code k3_describe_form} 自查，避免整次请求被不确定的子表单拖垮。</p>
 *
 * @author Kevin
 * @date 2026-10-21
 */
@Component
@ConditionalOnProperty(name = "enterprise.k3.enabled", havingValue = "true")
public class K3AccountBookQueryTool {

    /** 会计核算账簿表单标识。 */
    private static final String FORM_ID = "BD_AccountBook";

    /** 调整期间表单标识；属于现场待验证信息，查询失败不阻断主流程。 */
    private static final String ADJUST_PERIOD_FORM_ID = "GL_AdjustPeriod";

    /** 失败参数记忆有效期；超时后允许 Agent 携带修正参数重新验证。 */
    private static final long FAILURE_MEMORY_TTL_MS = 10L * 60 * 1000;

    /** 账簿单据头字段清单；顺序即金蝶返回列顺序，只保留业务可读字段。 */
    private static final List<String> BASE_FIELD_KEYS = List.of(
            "FNumber", "FName", "FDescription",
            "FDocumentStatus", "FForbidStatus",
            "FUseOrgId.FNumber", "FUseOrgId.FName",
            "FCURRENCYID.FNumber", "FCURRENCYID.FName",
            "FPeriodId", "FACCTTABLEID.FNumber", "FACCTTABLEID.FName",
            "FBOOKTYPE", "FUSEADJUSTPERIOD",
            "FINITIALSTATUS", "FCURRENTPERIOD", "FCURRENTYEAR",
            "FSTARTYEAR", "FSTARTPERIOD");

    /** 调整期间字段清单；对齐调整期间表截图的业务字段。 */
    private static final List<String> ADJUST_PERIOD_FIELD_KEYS = List.of(
            "FYEAR", "FADJUSTPERIOD", "FADJUSTSTATUS", "FADJUSTNAME", "FCOMMENT");

    /** 账簿稳定排序；基础资料数量有限，编码升序与 ERP 列表一致。 */
    private static final String DEFAULT_ORDER = "FNumber asc";

    /** 输出列的展示字段 Key，用于现场元数据列头翻译。 */
    private static final List<String> OUTPUT_FIELD_KEYS = List.of(
            "FNumber", "FName", "FDescription",
            "FDocumentStatus", "FForbidStatus",
            "FUseOrgId.FNumber", "FUseOrgId.FName",
            "FCURRENCYID.FNumber", "FCURRENCYID.FName",
            "FPeriodId", "FACCTTABLEID.FNumber", "FACCTTABLEID.FName",
            "FBOOKTYPE", "FUSEADJUSTPERIOD",
            "FINITIALSTATUS", "FCURRENTPERIOD", "FCURRENTYEAR",
            "FSTARTYEAR", "FSTARTPERIOD");

    private final K3Gateway gateway;
    private final KnowledgeStore knowledge;
    private final ErrorPatternCatalog errorPatterns;
    private final LargeResultExportService exportService;
    /** 现场元数据列头翻译器，保证用户看到中文名而不是内部字段 Key。 */
    private final K3FieldLocalizer fieldLocalizer;
    /** 记录最近被金蝶明确拒绝的参数签名；同参快速失败能打断 Agent 盲猜循环。 */
    private final ConcurrentHashMap<String, FailedCall> recentFailures = new ConcurrentHashMap<>();

    /**
     * 注入金蝶账簿查询依赖。
     *
     * @param gateway 金蝶 SDK 网关
     * @param knowledge 客户本地知识
     * @param errorPatterns 金蝶错误模式目录
     * @param exportService 大结果统一导出服务
     * @param fieldLocalizer 现场元数据列头翻译器
     */
    public K3AccountBookQueryTool(K3Gateway gateway, KnowledgeStore knowledge,
                                  ErrorPatternCatalog errorPatterns,
                                  LargeResultExportService exportService,
                                  K3FieldLocalizer fieldLocalizer) {
        this.gateway = gateway;
        this.knowledge = knowledge;
        this.errorPatterns = errorPatterns;
        this.exportService = exportService;
        this.fieldLocalizer = fieldLocalizer;
    }

    /**
     * 按编码或名称模糊查询会计核算账簿，返回 ERP 账簿列表的业务可读字段。
     *
     * <p>keyword 为空表示全量搜索；纯数字优先按编码模糊匹配，否则编码和名称
     * 双字段模糊匹配。FilterString 是金蝶表达式而非 SQL，单引号已转义。
     * 调整期间子表单 FormId 尚未经真实账套验证，查询失败时只附加警告，
     * 不让账簿主结果失败。</p>
     *
     * @param keyword 编码或名称关键字，为空时全量搜索
     * @param limit 单页行数，默认 50，最大 200
     * @param includeAdjustPeriod 是否附带返回各账簿的调整期间列表，默认否
     * @param requestContext MCP 请求上下文，由安全切面校验
     * @return MCP 返回体：inline 携带账簿行和可选调整期间，file 携带下载引用
     */
    @McpTool(name = "k3_query_account_book",
            description = "金蝶固定工具：当用户意图是账簿、核算账簿、会计账簿，"
                    + "或凭证汇总等查询需要先确定账簿编码或名称时，直接调用本工具，"
                    + "不要再用 k3_query_data 手工拼 BD_AccountBook 查询。"
                    + "支持按编码或名称模糊查询，keyword 为空时全量搜索；"
                    + "返回账簿编码、名称、使用组织、记账本位币、当前期间等业务字段。"
                    + "includeAdjustPeriod=true 时附带调整期间列表；"
                    + "调整期间数据失败不影响账簿主结果，只返回 adjustPeriodWarning。"
                    + "返回 mode=file 时结果已在服务器 CSV 中，必须立即使用 downloadUrl 下载并保存给用户。",
            generateOutputSchema = true)
    @PublicMcpTool(capabilityId = "k3.query-account-book", description = "查询会计核算账簿")
    public Map<String, Object> queryAccountBooks(
            @McpToolParam(description = "编码或名称关键字，支持模糊匹配；为空时全量搜索", required = false) String keyword,
            @McpToolParam(description = "单页行数，默认 50，最大 200", required = false) Integer limit,
            @McpToolParam(description = "是否附带返回调整期间列表，默认否", required = false) Boolean includeAdjustPeriod,
            McpSyncRequestContext requestContext) {

        int size = limit == null ? 50 : limit;
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("K3 account book limit must be between 1 and 200: " + size);
        }
        String effectiveKeyword = keyword == null ? "" : keyword.trim();
        String filter = buildFilter(effectiveKeyword);
        String fieldKeysJoined = String.join(",", BASE_FIELD_KEYS);
        rejectRepeatedFailure(fieldKeysJoined, filter, size);

        List<Map<String, Object>> books = fetchPage(FORM_ID, fieldKeysJoined, BASE_FIELD_KEYS, filter,
                DEFAULT_ORDER, 0, size);
        List<List<Object>> rows = new ArrayList<>(books.size());
        for (Map<String, Object> book : books) {
            List<Object> row = new ArrayList<>(BASE_FIELD_KEYS.size());
            for (String key : BASE_FIELD_KEYS) {
                row.add(book.get(key));
            }
            rows.add(row);
        }
        String adjustPeriodWarning = null;
        List<List<Object>> adjustPeriodRows = List.of();
        if (Boolean.TRUE.equals(includeAdjustPeriod)) {
            AdjustPeriodOutcome outcome = fetchAdjustPeriods();
            adjustPeriodWarning = outcome.warning();
            adjustPeriodRows = outcome.rows();
        }

        List<String> outputFields = localizedOutputFields();
        int estimateBytes = estimateSerializedBytes(rows);
        knowledge.observe(KnowledgeEntry.Layer.ENTITY, "form:" + FORM_ID, "present",
                30L * 24 * 3600);
        knowledge.observe(KnowledgeEntry.Layer.ENTITY, "field:" + FORM_ID + ":FIELDS",
                "valid", 30L * 24 * 3600);
        Map<String, Object> summary = buildSummary(effectiveKeyword, size, books.size(),
                adjustPeriodWarning);
        if (exportService.isInline(rows.size(), estimateBytes)) {
            ExportedQueryResult result = ExportedQueryResult.inline(FORM_ID, outputFields, rows,
                    false, summary);
            return toMcpResult(result, adjustPeriodRows, adjustPeriodWarning);
        }
        LargeResultExportService.StreamingExport export =
                exportService.beginExport(FORM_ID, outputFields);
        try {
            for (List<Object> row : rows) {
                exportService.appendRow(export, row);
            }
        } catch (RuntimeException exception) {
            exportService.cancelExport(export);
            throw exception;
        }
        return toMcpResult(exportService.finishExport(export), adjustPeriodRows, adjustPeriodWarning);
    }

    /**
     * 构造账簿查询的金蝶过滤表达式。
     *
     * <p>关键字为空时返回空串表示全量查询；纯数字按编码模糊匹配，否则编码和
     * 名称双字段模糊匹配，满足"用编码或名称模糊查询"的语义。</p>
     *
     * @param keyword 已规整的关键字
     * @return 金蝶过滤表达式，全量查询时为空串
     */
    private static String buildFilter(String keyword) {
        if (keyword.isEmpty()) {
            return "";
        }
        String escaped = escape(keyword);
        // 纯数字关键字大概率是编码，编码命中率优先于名称模糊。
        if (keyword.matches("[0-9]+")) {
            return "FNumber like '%" + escaped + "%'";
        }
        return "FNumber like '%" + escaped + "%' or FName like '%" + escaped + "%'";
    }

    /**
     * 分页拉取账簿数据并转为以字段 Key 为键的纯 Java 行。
     *
     * @param fieldKeysJoined 请求字段 Key 串，用于失败签名
     * @param fieldKeys 本次请求的字段 Key
     * @param filter 当前过滤表达式
     * @param order 排序表达式
     * @param startRow 起始行
     * @param limit 单页行数
     * @return 键值行列表
     */
    private List<Map<String, Object>> fetchPage(String formId, String fieldKeysJoined, List<String> fieldKeys,
                                                String filter, String order,
                                                int startRow, int limit) {
        String raw = gateway.query(formId, String.join(",", fieldKeys), filter,
                order, startRow, limit);
        JsonElement parsed = JsonParser.parseString(raw);
        JsonArray rows = parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        if (rows == null) {
            throw new IllegalStateException("K3 account book response is not a row array");
        }
        rejectEmbeddedQueryErrors(rows, fieldKeysJoined, filter, limit);
        List<Map<String, Object>> entries = new ArrayList<>(rows.size());
        for (JsonElement rowElement : rows) {
            if (rowElement.isJsonArray()) {
                entries.add(zipRow(fieldKeys, (List<Object>) toPlainObject(rowElement)));
            }
        }
        return entries;
    }

    /**
     * 拉取调整期间列表。
     *
     * <p>调整期间表单 FormId 未经真实账套验证，任何失败都降级为有界警告，
     * 保证账簿主结果不受子表单可用性影响。</p>
     *
     * @return 调整期间结果：rows 为期间行，warning 为失败原因
     */
    private AdjustPeriodOutcome fetchAdjustPeriods() {
        String fieldKeysJoined = String.join(",", ADJUST_PERIOD_FIELD_KEYS);
        try {
            List<Map<String, Object>> periods = fetchPage(ADJUST_PERIOD_FORM_ID, fieldKeysJoined,
                    ADJUST_PERIOD_FIELD_KEYS, "", "FYEAR asc, FADJUSTPERIOD asc", 0, 200);
            List<List<Object>> rows = new ArrayList<>(periods.size());
            for (Map<String, Object> period : periods) {
                List<Object> row = new ArrayList<>(ADJUST_PERIOD_FIELD_KEYS.size());
                for (String key : ADJUST_PERIOD_FIELD_KEYS) {
                    row.add(period.get(key));
                }
                rows.add(row);
            }
            return new AdjustPeriodOutcome(rows, null);
        } catch (RuntimeException exception) {
            // 子表单不确定是预期内的现场差异；降级为警告而不是让账簿查询失败。
            return new AdjustPeriodOutcome(List.of(),
                    "调整期间数据不可用（FormId " + ADJUST_PERIOD_FORM_ID
                            + " 可能不正确）: " + exception.getMessage()
                            + "；可用 k3_describe_form 或 k3_search_forms 核对真实 FormId");
        }
    }

    /**
     * 翻译账簿输出列头；现场元数据缺失时使用字段 Key 兜底。
     *
     * @return 输出列头
     */
    private List<String> localizedOutputFields() {
        return fieldLocalizer.fieldNames(FORM_ID, OUTPUT_FIELD_KEYS);
    }

    /**
     * 构造查询条件与结果规模摘要。
     *
     * @param keyword 已规整的关键字
     * @param limit 单页行数
     * @param bookCount 返回的账簿数量
     * @param adjustPeriodWarning 调整期间警告，可为 null
     * @return 摘要映射
     */
    private static Map<String, Object> buildSummary(String keyword, int limit,
                                                    int bookCount, String adjustPeriodWarning) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("keyword", keyword);
        summary.put("limit", limit);
        summary.put("bookCount", bookCount);
        if (adjustPeriodWarning != null) {
            summary.put("adjustPeriodWarning", adjustPeriodWarning);
        }
        return summary;
    }

    /**
     * 把统一查询结果转换为 MCP 最终返回体，并附加调整期间数据。
     *
     * <p>Spring AI 会把 record 组件全部生成为 required 输出字段，inline 模式的
     * file 专属字段为 null 时会被客户端 schema 校验拒收。这里按模式裁剪字段，
     * 不携带 null 占位。</p>
     *
     * @param result 服务层统一结果
     * @param adjustPeriodRows 调整期间行，仅 includeAdjustPeriod=true 且成功时非空
     * @param adjustPeriodWarning 调整期间警告，可为 null
     * @return MCP 可安全校验和序列化的返回体
     */
    private static Map<String, Object> toMcpResult(ExportedQueryResult result,
                                                   List<List<Object>> adjustPeriodRows,
                                                   String adjustPeriodWarning) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", result.mode());
        payload.put("queryId", result.queryId());
        payload.put("totalCount", result.totalCount());
        payload.put("fields", result.fields());
        payload.put("summary", result.summary());
        if (ExportedQueryResult.MODE_INLINE.equals(result.mode())) {
            payload.put("rows", result.rows());
            payload.put("hasMore", result.hasMore());
        } else {
            payload.put("fileId", result.fileId());
            payload.put("fileName", result.fileName());
            payload.put("downloadUrl", result.downloadUrl());
            payload.put("downloadExpiresAt", result.downloadExpiresAt());
            payload.put("fileExpiresAt", result.fileExpiresAt());
            payload.put("instruction", result.instruction());
        }
        if (adjustPeriodWarning != null) {
            payload.put("adjustPeriodWarning", adjustPeriodWarning);
        }
        if (!adjustPeriodRows.isEmpty()) {
            payload.put("adjustPeriodFields",
                    List.of("会计年度", "调整期间", "状态", "名称", "说明"));
            payload.put("adjustPeriodRows", adjustPeriodRows);
        }
        return payload;
    }

    /**
     * 估算输出行的序列化字节数；按字符串 UTF-16 长度近似。
     *
     * @param rows 输出行
     * @return 估算字节数
     */
    private static int estimateSerializedBytes(List<List<Object>> rows) {
        int bytes = 0;
        for (List<Object> row : rows) {
            for (Object value : row) {
                bytes += value == null ? 4 : String.valueOf(value).length() * 2 + 8;
            }
            bytes += 16;
        }
        return bytes;
    }

    /**
     * 把 Gson JsonElement 转为纯 Java 集合，保证 MCP 返回值可被 Jackson 安全序列化。
     *
     * @param element 待转换的 JSON 元素
     * @return 可安全序列化的 Java 对象
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

    /**
     * 把字段 Key 与返回列组装为键值行，避免后续处理依赖易错的下标。
     *
     * @param fieldKeys 请求字段 Key
     * @param row 原始返回行
     * @return 键值行
     */
    private static Map<String, Object> zipRow(List<String> fieldKeys, List<Object> row) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (int index = 0; index < fieldKeys.size(); index++) {
            mapped.put(fieldKeys.get(index), index < row.size() ? row.get(index) : null);
        }
        return mapped;
    }

    /**
     * 转义金蝶过滤表达式中的单引号。
     *
     * @param value 原始值
     * @return 转义后的值
     */
    private static String escape(String value) {
        return value.replace("'", "''");
    }

    /**
     * 拒绝金蝶在 ExecuteBillQuery 响应内嵌的业务错误，防止把错误行当数据学习。
     *
     * @param rows 金蝶返回的二维查询结果
     * @param fieldKeysJoined 请求字段 Key 串
     * @param filterString 当前过滤表达式
     * @param limit 当前分页行数
     */
    private void rejectEmbeddedQueryErrors(JsonArray rows, String fieldKeysJoined,
                                           String filterString, int limit) {
        for (JsonElement row : rows) {
            if (!row.isJsonArray() || row.getAsJsonArray().isEmpty()) {
                continue;
            }
            JsonElement first = row.getAsJsonArray().get(0);
            if (!first.isJsonObject()) {
                continue;
            }
            JsonObject cell = first.getAsJsonObject();
            JsonObject status = cell.has("Result") && cell.get("Result").isJsonObject()
                    ? cell.getAsJsonObject("Result").getAsJsonObject("ResponseStatus") : null;
            if (status == null) {
                continue;
            }
            recordFailure(fieldKeysJoined, filterString, limit, status);
            throw new K3BusinessException(toBusinessExceptionAdvice(status));
        }
    }

    /**
     * 匹配金蝶错误模式并沉淀负向知识，供 Agent 一次自纠而不是反复盲猜。
     *
     * @param status 金蝶 ResponseStatus 对象
     * @return 有界错误描述；匹配到模式时附修正建议
     */
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

    /**
     * 提取金蝶 ResponseStatus 中的有界错误描述。
     *
     * @param status 金蝶 ResponseStatus 对象
     * @return 逗号分隔的最多 5 条错误描述
     */
    private static String describeResponseStatusErrors(JsonObject status) {
        List<String> errors = new ArrayList<>();
        if (status == null || !status.has("Errors") || !status.get("Errors").isJsonArray()) {
            return "金蝶返回业务失败";
        }
        for (JsonElement element : status.getAsJsonArray("Errors")) {
            if (errors.size() >= 5) {
                break;
            }
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
            String joined = List.of(field, message, description).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.joining(": "));
            if (!joined.isBlank() && !errors.contains(joined)) {
                errors.add(joined);
            }
        }
        String joined = String.join("; ", errors);
        return joined.isBlank() ? "金蝶返回业务失败" : joined;
    }

    /**
     * 在 TTL 内直接拒绝刚刚被金蝶明确拒绝的相同参数，强制 Agent 改参数或先确认。
     *
     * @param signatureParts 参与失败签名的参数
     */
    private void rejectRepeatedFailure(Object... signatureParts) {
        String signature = signature(signatureParts);
        FailedCall failed = recentFailures.get(signature);
        if (failed != null && System.currentTimeMillis() - failed.at() < FAILURE_MEMORY_TTL_MS) {
            throw new K3BusinessException("该参数组合刚被金蝶拒绝: " + failed.advice()
                    + "。请修正过滤条件，不要原样重试。");
        }
        recentFailures.values().removeIf(entry -> System.currentTimeMillis() - entry.at()
                >= FAILURE_MEMORY_TTL_MS);
    }

    /**
     * 记录金蝶明确业务失败的参数签名和有界修正建议；签名必须与
     * {@link #rejectRepeatedFailure(Object...)} 的签名结构一致。
     *
     * @param fieldKeysJoined 请求字段 Key 串
     * @param filterSignature 当前过滤签名
     * @param limit 当前分页行数
     * @param status 金蝶 ResponseStatus 对象
     */
    private void recordFailure(String fieldKeysJoined, String filterSignature, int limit,
                               JsonObject status) {
        if (status == null) {
            throw new IllegalArgumentException("K3 failure status is required");
        }
        // 只保留少量最近失败，避免长驻进程积累；建议文本已在错误匹配阶段做有界截断。
        if (recentFailures.size() > 128) {
            recentFailures.clear();
        }
        recentFailures.put(signature(fieldKeysJoined, filterSignature, limit),
                new FailedCall(System.currentTimeMillis(), toBusinessExceptionAdvice(status)));
    }

    /**
     * 规范化失败参数签名。
     *
     * @param signatureParts 参与签名的参数
     * @return 竖线分隔的参数签名
     */
    private static String signature(Object... signatureParts) {
        return Arrays.stream(signatureParts)
                .map(value -> value == null ? "" : String.valueOf(value))
                .collect(Collectors.joining("|"));
    }

    /** 保留最近失败调用的参数签名和建议，仅用于打断重复盲猜。 */
    private record FailedCall(long at, String advice) {
    }

    /**
     * 调整期间查询结果。
     *
     * @param rows 调整期间行
     * @param warning 查询失败原因，成功时为 null
     */
    private record AdjustPeriodOutcome(List<List<Object>> rows, String warning) {
    }
}
