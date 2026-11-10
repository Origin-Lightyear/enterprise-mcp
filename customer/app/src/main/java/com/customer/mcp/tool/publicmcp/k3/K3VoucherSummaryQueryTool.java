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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 固定查询凭证汇总，基于 GL_Voucher_New 凭证分录在服务端聚合出 ERP 凭证汇总表口径。
 *
 * <p>真实账套已证实 GL_VOUCHERSUMMARY 是过滤方案存储容器，不能通过
 * ExecuteBillQuery 返回汇总结果；GL_RPT_VoucherSummary 报表通道也无法用
 * GetSysReportData 参数稳定驱动。因此本工具直接分页查询 GL_Voucher_New 分录，
 * 先用账簿、币别、日期、过账状态和凭证字收窄数据，再按科目编码前缀聚合成
 * 科目级别 1、2、3 的借贷汇总。科目名称优先取编码长度恰好等于级别长度的分录；
 * 返回末行是服务端重算的合计行。凭证字必须至少选一个，组内号段当前不支持。
 * 调整期过滤尚未在分录查询中校准，启用该参数时会在摘要中明确提示未生效。
 * 账簿、币别或凭证字不确定时应先向用户确认。</p>
 *
 * @author Kevin
 * @date 2026-10-21
 */
@Component
@ConditionalOnProperty(name = "enterprise.k3.enabled", havingValue = "true")
public class K3VoucherSummaryQueryTool {

    /** ERP 凭证汇总界面对应的过滤方案 FormId，仅用于输出列头现场翻译。 */
    private static final String SUMMARY_FORM_ID = "GL_VOUCHERSUMMARY";

    /** 凭证分录数据源；汇总由本工具按科目编码前缀计算。 */
    private static final String DATA_FORM_ID = "GL_Voucher_New";

    /** 失败参数记忆有效期；超时后允许 Agent 携带修正参数重新验证。 */
    private static final long FAILURE_MEMORY_TTL_MS = 10L * 60 * 1000;

    /** 单页拉取分录行数；与 ExecuteBillQuery 实测可用上限一致。 */
    private static final int PAGE_SIZE = 100;

    /** 聚合分录所需的固定字段；顺序同时兼容金蝶数组行解析。 */
    private static final String ENTRY_FIELD_KEYS = String.join(",", List.of(
            "FACCOUNTID.FNumber", "FACCOUNTID.FName", "FACCOUNTID.FFullName",
            "FDEBIT", "FCREDIT"));

    /** 科目级别 1、2、3 对应的编码前缀长度；短编码按自身长度容错。 */
    private static final List<Integer> LEVEL_PREFIX_LENGTHS = List.of(4, 7, 10);

    /** 输出列的展示字段 Key，用于现场元数据列头翻译。 */
    private static final List<String> OUTPUT_FIELD_KEYS = List.of(
            "FACCOUNTNUMBER", "FACCOUNTNAME", "FDEBIT", "FCREDIT");

    /** 分录按科目编码排序，保证多页拉取和聚合名称观察顺序稳定。 */
    private static final String ENTRY_ORDER = "FACCOUNTID.FNumber";

    private final K3Gateway gateway;
    private final KnowledgeStore knowledge;
    private final ErrorPatternCatalog errorPatterns;
    private final LargeResultExportService exportService;
    /** 现场元数据列头翻译器，保证用户看到中文名而不是内部字段 Key。 */
    private final K3FieldLocalizer fieldLocalizer;
    /** 记录最近被金蝶明确拒绝的过滤签名；同参快速失败能打断 Agent 盲猜循环。 */
    private final ConcurrentHashMap<String, FailedCall> recentFailures = new ConcurrentHashMap<>();

    /**
     * 注入金蝶凭证汇总查询依赖。
     *
     * @param gateway 金蝶 SDK 网关
     * @param knowledge 客户本地知识
     * @param errorPatterns 金蝶错误模式目录
     * @param exportService 大结果统一导出服务
     * @param fieldLocalizer 现场元数据列头翻译器
     */
    public K3VoucherSummaryQueryTool(K3Gateway gateway, KnowledgeStore knowledge,
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
     * 按凭证汇总表条件查询并返回科目级借贷汇总，结果样式对齐 ERP 凭证汇总表。
     *
     * <p>凭证字必须至少选择一个；每个凭证字单独查询分录后由服务端合并，
     * 因此凭证张数和附件数不在此处推断。凭证范围直接翻译为 FPOSTERID 条件：
     * 所有凭证不加限制，已过账凭证要求大于 0，未过账凭证要求等于 0 或为空。
     * 借方与贷方合计在服务端重算，末行固定为合计行；不超过 100 行时直接内联渲染。</p>
     *
     * @param accountBook 账簿编码或名称，纯数字按编码处理
     * @param currencyName 币别名称，例如人民币
     * @param startDate 开始日期，格式 yyyy-MM-dd 或 yyyy/M/d
     * @param endDate 结束日期，格式同开始日期
     * @param voucherRange 凭证范围：ALL 所有凭证、POSTED 已过账凭证、UNPOSTED 未过账凭证
     * @param voucherGroups 凭证字列表，至少一个；暂不支持组内号段
     * @param accountLevel 科目汇总级别，1、2 或 3，默认 1
     * @param showAccountFullName 是否显示科目全名，默认否
     * @param excludeAdjustVoucher 是否排除调整期间凭证；当前通道未校准，传 true 只返回警告
     * @param requestContext MCP 请求上下文，由安全切面校验
     * @return MCP 返回体：inline 携带汇总行，file 携带下载引用
     */
    @McpTool(name = "k3_query_voucher_summary",
            description = "金蝶固定工具：当用户意图是凭证汇总表、科目借贷汇总或按凭证字汇总凭证时，"
                    + "直接调用本工具，不要再用 k3_query_data 手工拼 GL_VOUCHERSUMMARY 查询。"
                    + "数据源是 GL_Voucher_New 凭证分录，服务端按科目编码前缀聚合并对齐 ERP 凭证汇总表。"
                    + "结构化条件：账簿、币别、日期区间和凭证范围必填，凭证范围为所有凭证/已过账凭证/"
                    + "未过账凭证，科目级别只支持 1、2、3，凭证字必须至少选一个，暂不支持组内号段。"
                    + "返回科目编码、科目名称、借方金额、贷方金额和合计行；返回 mode=file 时结果已在"
                    + "服务器 CSV 中，必须立即使用 downloadUrl 下载并保存给用户。关键条件不确定时先向用户确认。",
            generateOutputSchema = true)
    @PublicMcpTool(capabilityId = "k3.query-voucher-summary", description = "查询凭证汇总表")
    public Map<String, Object> queryVoucherSummary(
            @McpToolParam(description = "账簿编码或名称，纯数字按编码处理", required = true) String accountBook,
            @McpToolParam(description = "币别名称，例如人民币", required = true) String currencyName,
            @McpToolParam(description = "开始日期，格式 yyyy-MM-dd 或 yyyy/M/d", required = true) String startDate,
            @McpToolParam(description = "结束日期，格式同开始日期", required = true) String endDate,
            @McpToolParam(description = "凭证范围：ALL 所有凭证、POSTED 已过账凭证、UNPOSTED 未过账凭证", required = true) String voucherRange,
            @McpToolParam(description = "凭证字列表，至少一个，例如 收、付、转；暂不支持组内号段", required = true) List<String> voucherGroups,
            @McpToolParam(description = "科目汇总级别：1、2 或 3，默认 1", required = false) Integer accountLevel,
            @McpToolParam(description = "是否显示科目全名，默认否", required = false) Boolean showAccountFullName,
            @McpToolParam(description = "是否排除调整期间凭证，默认否；当前分录聚合通道未校准该过滤", required = false) Boolean excludeAdjustVoucher,
            McpSyncRequestContext requestContext) {

        List<String> groupNames = parseVoucherGroups(voucherGroups);
        LocalDate start = parseDate(startDate, "startDate");
        LocalDate end = parseDate(endDate, "endDate");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("K3 voucher summary startDate is after endDate");
        }
        int level = accountLevel == null ? 1 : accountLevel;
        if (level < 1 || level > 3) {
            throw new IllegalArgumentException(
                    "K3 voucher summary accountLevel must be 1, 2 or 3: " + level);
        }
        VoucherRangeType rangeType = VoucherRangeType.parse(
                voucherRange == null ? "" : voucherRange.trim().toUpperCase(Locale.ROOT));
        boolean fullName = Boolean.TRUE.equals(showAccountFullName);
        boolean excludeAdjust = Boolean.TRUE.equals(excludeAdjustVoucher);

        Aggregation aggregation = new Aggregation();
        for (String groupName : groupNames) {
            String filter = buildFilter(accountBook, currencyName, start, end,
                    rangeType, groupName);
            String signature = filter + "|level=" + level + "|fullName=" + fullName
                    + "|excludeAdjust=" + excludeAdjust;
            List<VoucherEntry> entries = fetchAllRows(filter, signature, level);
            aggregation.mergeEntries(entries, level, fullName);
        }

        List<List<Object>> rows = aggregation.toRows();
        knowledge.observe(KnowledgeEntry.Layer.ENTITY, "form:" + DATA_FORM_ID, "present",
                30L * 24 * 3600);
        knowledge.observe(KnowledgeEntry.Layer.ENTITY,
                "field:" + DATA_FORM_ID + ":SUMMARY_ENTRY_FIELDS", "valid", 30L * 24 * 3600);
        List<String> outputFields = localizedOutputFields();
        int estimateBytes = estimateSerializedBytes(rows);
        if (exportService.isInline(rows.size(), estimateBytes)) {
            return toMcpResult(ExportedQueryResult.inline(SUMMARY_FORM_ID, outputFields, rows,
                    false, buildSummary(accountBook, currencyName, start, end,
                    rangeType, groupNames, level, excludeAdjust, aggregation)));
        }
        LargeResultExportService.StreamingExport export =
                exportService.beginExport(SUMMARY_FORM_ID, outputFields);
        try {
            for (List<Object> row : rows) {
                exportService.appendRow(export, row);
            }
        } catch (RuntimeException exception) {
            exportService.cancelExport(export);
            throw exception;
        }
        return toMcpResult(exportService.finishExport(export));
    }

    /**
     * 解析并校验凭证字条件；凭证字为空直接拒绝，与 ERP 必选约束一致。
     *
     * @param voucherGroups 原始凭证字参数
     * @return 去重后的凭证字名称
     */
    private static List<String> parseVoucherGroups(List<String> voucherGroups) {
        if (voucherGroups == null || voucherGroups.isEmpty()) {
            throw new IllegalArgumentException("K3 voucher summary requires at least one voucher group");
        }
        List<String> names = new ArrayList<>();
        for (String raw : voucherGroups) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("K3 voucher summary voucher group is blank");
            }
            String name = raw.trim();
            if (name.indexOf(':') >= 0 || name.indexOf('：') >= 0) {
                throw new IllegalArgumentException(
                        "K3 voucher summary voucher group range is unsupported: " + name
                                + "; voucher entry aggregation does not support group ranges");
            }
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * 解析日期参数，兼容 ERP 风格斜杠与 ISO 横线格式。
     *
     * @param value 原始日期字符串
     * @param paramName 参数名，用于错误提示
     * @return 解析后的日期
     */
    private static LocalDate parseDate(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("K3 voucher summary " + paramName + " is required");
        }
        String text = value.trim();
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException ignored) {
            // 金蝶界面常用 yyyy/M/d，补一个宽松解析路径后再统一抛出明确错误。
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ofPattern("yyyy/M/d"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "K3 voucher summary " + paramName + " is invalid: " + value);
        }
    }

    /**
     * 构造单个凭证字的 GL_Voucher_New 过滤表达式。
     *
     * <p>日期使用已实测可用的带引号字面量，不使用金蝶不支持的数据库函数。
     * FPOSTERID 是过账人内码：大于 0 表示已过账，等于 0 或为空表示未过账。</p>
     *
     * @param accountBook 账簿编码或名称
     * @param currencyName 币别名称
     * @param start 开始日期
     * @param end 结束日期
     * @param rangeType 凭证范围
     * @param groupName 凭证字名称
     * @return 金蝶 FilterString
     */
    private static String buildFilter(String accountBook, String currencyName,
                                      LocalDate start, LocalDate end,
                                      VoucherRangeType rangeType, String groupName) {
        if (accountBook == null || accountBook.isBlank()) {
            throw new IllegalArgumentException("K3 voucher summary accountBook is required");
        }
        if (currencyName == null || currencyName.isBlank()) {
            throw new IllegalArgumentException("K3 voucher summary currencyName is required");
        }
        String book = accountBook.trim();
        String bookCondition = book.matches("[0-9]+")
                ? "FAccountBookID.FNumber='" + escapeFilterText(book) + "'"
                : "FAccountBookID.FName='" + escapeFilterText(book) + "'";
        List<String> conditions = new ArrayList<>();
        conditions.add(bookCondition);
        conditions.add("FDate>='" + start + "'");
        conditions.add("FDate<='" + end + "'");
        conditions.add("FCURRENCYID.FName='" + escapeFilterText(currencyName.trim()) + "'");
        if (rangeType == VoucherRangeType.POSTED) {
            conditions.add("FPOSTERID>0");
        } else if (rangeType == VoucherRangeType.UNPOSTED) {
            conditions.add("(FPOSTERID=0 or FPOSTERID is null)");
        }
        conditions.add("FVOUCHERGROUPID.FName='" + escapeFilterText(groupName) + "'");
        return String.join(" and ", conditions);
    }

    /**
     * 转义 FilterString 中的单引号，避免业务名称破坏表达式结构。
     *
     * @param value 原始业务文本
     * @return 可放入单引号字面量的文本
     */
    private static String escapeFilterText(String value) {
        return value.replace("'", "''");
    }

    /**
     * 分页拉取单个凭证字的凭证分录。
     *
     * @param filter 金蝶过滤表达式
     * @param signature 当前过滤签名，用于失败记忆
     * @param level 科目汇总级别，用于失败签名和错误提示
     * @return 凭证分录
     */
    private List<VoucherEntry> fetchAllRows(String filter, String signature, int level) {
        rejectRepeatedFailure(signature, level);
        List<VoucherEntry> rows = new ArrayList<>();
        int startRow = 0;
        while (true) {
            String raw = gateway.query(DATA_FORM_ID, ENTRY_FIELD_KEYS, filter,
                    ENTRY_ORDER, startRow, PAGE_SIZE);
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(raw);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("K3 voucher summary response is not JSON", exception);
            }
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("K3 voucher summary response is not an object");
            }
            JsonObject response = parsed.getAsJsonObject();
            JsonObject result = response.has("Result") && response.get("Result").isJsonObject()
                    ? response.getAsJsonObject("Result") : response;
            JsonObject status = result.has("ResponseStatus") && result.get("ResponseStatus").isJsonObject()
                    ? result.getAsJsonObject("ResponseStatus") : null;
            if (status != null && status.has("IsSuccess") && !status.get("IsSuccess").getAsBoolean()) {
                recordFailure(signature, level, status);
            }
            if (!result.has("Rows") || !result.get("Rows").isJsonArray()) {
                throw new IllegalStateException("K3 voucher summary response has no Rows array");
            }
            JsonArray batch = result.getAsJsonArray("Rows");
            for (JsonElement rowElement : batch) {
                VoucherEntry entry = parseEntryRow(rowElement);
                if (entry != null) {
                    rows.add(entry);
                }
            }
            if (batch.size() < PAGE_SIZE) {
                return rows;
            }
            startRow += batch.size();
        }
    }

    /**
     * 把一页中的一行分录解析为聚合模型，兼容数组行和对象行。
     *
     * @param rowElement 金蝶分录行
     * @return 凭证分录；空行或编码为空时返回 null
     */
    private static VoucherEntry parseEntryRow(JsonElement rowElement) {
        if (rowElement == null || rowElement.isJsonNull()) {
            return null;
        }
        String number;
        String name;
        String fullName;
        BigDecimal debit;
        BigDecimal credit;
        if (rowElement.isJsonArray()) {
            JsonArray values = rowElement.getAsJsonArray();
            number = text(arrayValue(values, 0));
            name = text(arrayValue(values, 1));
            fullName = text(arrayValue(values, 2));
            debit = amount(arrayValue(values, 3));
            credit = amount(arrayValue(values, 4));
        } else if (rowElement.isJsonObject()) {
            JsonObject row = rowElement.getAsJsonObject();
            number = text(valueAt(row, "FACCOUNTID.FNumber"));
            name = text(valueAt(row, "FACCOUNTID.FName"));
            fullName = text(valueAt(row, "FACCOUNTID.FFullName"));
            debit = amount(valueAt(row, "FDEBIT"));
            credit = amount(valueAt(row, "FCREDIT"));
        } else {
            return null;
        }
        if (number.isBlank()) {
            return null;
        }
        return new VoucherEntry(number, name, fullName, debit, credit);
    }

    /**
     * 读取数组行指定位置的原始文本。
     *
     * @param values 分录数组
     * @param index 字段位置
     * @return 原始值；越界时返回 null
     */
    private static JsonElement arrayValue(JsonArray values, int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    /**
     * 读取对象行字段并容忍 Gson 的 null 包装。
     *
     * @param row 分录对象
     * @param key 字段 Key
     * @return 原始值；缺失时返回 null
     */
    private static JsonElement valueAt(JsonObject row, String key) {
        return row.has(key) ? row.get(key) : null;
    }

    /**
     * 翻译汇总输出列头；现场元数据缺失时使用 ERP 界面列名兜底。
     *
     * @return 输出列头
     */
    private List<String> localizedOutputFields() {
        List<String> names = fieldLocalizer.fieldNames(SUMMARY_FORM_ID, OUTPUT_FIELD_KEYS);
        List<String> output = new ArrayList<>(names.size());
        for (String name : names) {
            output.add(name.equals("FACCOUNTNUMBER") ? "科目编码"
                    : name.equals("FACCOUNTNAME") ? "科目名称"
                    : name.equals("FDEBIT") ? "借方金额"
                    : name.equals("FCREDIT") ? "贷方金额" : name);
        }
        return output;
    }

    /**
     * 构造查询条件与全局合计摘要。
     *
     * @param accountBook 账簿
     * @param currencyName 币别
     * @param start 开始日期
     * @param end 结束日期
     * @param rangeType 凭证范围
     * @param groups 凭证字名称
     * @param level 科目级别
     * @param excludeAdjust 是否请求排除调整期凭证
     * @param aggregation 聚合结果
     * @return 摘要映射
     */
    private static Map<String, Object> buildSummary(String accountBook, String currencyName,
                                                    LocalDate start, LocalDate end,
                                                    VoucherRangeType rangeType,
                                                    List<String> groups,
                                                    int level, boolean excludeAdjust,
                                                    Aggregation aggregation) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accountBook", accountBook);
        summary.put("currency", currencyName);
        summary.put("startDate", start.toString());
        summary.put("endDate", end.toString());
        summary.put("voucherRange", rangeType.label());
        summary.put("voucherGroups", groups);
        summary.put("accountLevel", level);
        summary.put("rowCount", aggregation.groups().size());
        summary.put("totalDebit", plainAmount(aggregation.totalDebit()));
        summary.put("totalCredit", plainAmount(aggregation.totalCredit()));
        summary.put("dataSource", DATA_FORM_ID);
        if (excludeAdjust) {
            summary.put("warning", "当前 GL_Voucher_New 聚合通道尚未校准调整期凭证过滤，"
                    + "excludeAdjustVoucher 本次未生效");
        }
        return summary;
    }

    /**
     * 把统一查询结果转换为 MCP 最终返回体。
     *
     * <p>Spring AI 会把 record 组件全部生成为 required 输出字段，inline 模式的
     * file 专属字段为 null 时会被客户端 schema 校验拒收。这里按模式裁剪字段，
     * 不携带 null 占位。</p>
     *
     * @param result 服务层统一结果
     * @return MCP 可安全校验和序列化的返回体
     */
    private static Map<String, Object> toMcpResult(ExportedQueryResult result) {
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
        return payload;
    }

    /**
     * 估算汇总行的序列化字节数；按字符串 UTF-16 长度近似。
     *
     * @param rows 汇总行
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
     * 把 Gson 值转成无空白字符串；null 统一为空串。
     *
     * @param value 原始值
     * @return 字符串值
     */
    private static String text(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.getAsString().trim();
    }

    /**
     * 把金额文本规整为 BigDecimal；括号负数按负值处理，不可解析值按 0 处理。
     *
     * @param value 原始金额
     * @return 金额
     */
    private static BigDecimal amount(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return BigDecimal.ZERO;
        }
        String raw = value.getAsString().trim().replace(",", "");
        if (raw.startsWith("(") && raw.endsWith(")")) {
            raw = "-" + raw.substring(1, raw.length() - 1);
        }
        if (raw.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 规整金额展示，去掉无意义末尾零并避免负零。
     *
     * @param value 金额
     * @return 展示字符串
     */
    private static String plainAmount(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.signum() == 0) {
            return "0";
        }
        return stripped.toPlainString();
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
        String advice = matched == null ? rawError
                : rawError + " | 原因: " + matched.reason()
                + " | 建议: " + matched.suggestion()
                + (matched.nextActionTool() == null ? "" : " | 下一步: " + matched.nextActionTool());
        if (rawError.contains("字段不存在") || rawError.toLowerCase(Locale.ROOT).contains("field")) {
            advice = advice + " | 凭证汇总校准: 用 k3_describe_form 查询 " + DATA_FORM_ID
                    + "，核对 FAccountBookID、FDate、FCURRENCYID、FPOSTERID、"
                    + "FVOUCHERGROUPID、FACCOUNTID、FDEBIT、FCREDIT 真实 Key";
        }
        return advice;
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
     * @param signature 当前过滤签名
     * @param level 科目汇总级别
     */
    private void rejectRepeatedFailure(String signature, int level) {
        String key = signature + "|" + level;
        FailedCall failed = recentFailures.get(key);
        if (failed != null && System.currentTimeMillis() - failed.at() < FAILURE_MEMORY_TTL_MS) {
            throw new K3BusinessException("该参数组合刚被金蝶拒绝: " + failed.advice()
                    + "。请先核对分录字段，不要原样重试。");
        }
        recentFailures.values().removeIf(entry -> System.currentTimeMillis() - entry.at()
                >= FAILURE_MEMORY_TTL_MS);
    }

    /**
     * 记录金蝶明确业务失败的参数签名和有界修正建议。
     *
     * @param signature 当前过滤签名
     * @param level 科目汇总级别
     * @param status 金蝶 ResponseStatus 对象
     */
    private void recordFailure(String signature, int level, JsonObject status) {
        if (status == null) {
            throw new IllegalArgumentException("K3 failure status is required");
        }
        // 只保留少量最近失败，避免长驻进程积累；建议文本已在错误匹配阶段做有界截断。
        if (recentFailures.size() > 128) {
            recentFailures.clear();
        }
        recentFailures.put(signature + "|" + level,
                new FailedCall(System.currentTimeMillis(), toBusinessExceptionAdvice(status)));
        throw new K3BusinessException(toBusinessExceptionAdvice(status));
    }

    /** 保留最近失败调用的参数签名和建议，仅用于打断重复盲猜。 */
    private record FailedCall(long at, String advice) {
    }

    /**
     * 凭证范围枚举；查询条件使用 FPOSTERID 的底层内码语义。
     */
    private enum VoucherRangeType {

        /** 所有凭证，底层不限制 FPOSTERID。 */
        ALL("所有凭证"),

        /** 已过账凭证，底层语义是过账人内码 FPOSTERID > 0。 */
        POSTED("已过账凭证"),

        /** 未过账凭证，底层语义是 FPOSTERID = 0 或 is null。 */
        UNPOSTED("未过账凭证");

        /** ERP 界面展示名。 */
        private final String label;

        /**
         * 初始化凭证范围语义。
         *
         * @param label ERP 界面展示名
         */
        VoucherRangeType(String label) {
            this.label = label;
        }

        /**
         * 获取 ERP 界面展示名。
         *
         * @return 展示名
         */
        private String label() {
            return label;
        }

        /**
         * 解析凭证范围参数，未知值直接拒绝。
         *
         * @param value 大写枚举值
         * @return 凭证范围类型
         */
        private static VoucherRangeType parse(String value) {
            for (VoucherRangeType type : values()) {
                if (type.name().equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException(
                    "K3 voucher summary voucherRange is invalid: " + value);
        }
    }

    /**
     * 查询得到的单条凭证分录。
     */
    private record VoucherEntry(String number, String name, String fullName,
                                BigDecimal debit, BigDecimal credit) {
    }

    /**
     * 跨凭证字合并的科目汇总状态。
     */
    private static final class Aggregation {

        /** 分组科目状态，插入顺序保持首条分录返回顺序。 */
        private final Map<String, AccountGroup> groups = new LinkedHashMap<>();

        /**
         * 合并一个凭证字的分录；按科目编码前缀截取到请求级别。
         *
         * @param entries 单个凭证字的分录
         * @param level 科目级别
         * @param fullName 是否优先显示科目全名
         */
        private void mergeEntries(List<VoucherEntry> entries, int level, boolean fullName) {
            int prefixLength = LEVEL_PREFIX_LENGTHS.get(level - 1);
            for (VoucherEntry entry : entries) {
                String number = entry.number();
                String groupNumber = number.substring(0, Math.min(prefixLength, number.length()));
                AccountGroup group = groups.computeIfAbsent(groupNumber, AccountGroup::new);
                group.debit = group.debit.add(entry.debit());
                group.credit = group.credit.add(entry.credit());
                String candidate = fullName && !entry.fullName().isBlank()
                        ? entry.fullName() : entry.name();
                group.observeName(candidate, number.length() == groupNumber.length());
            }
        }

        /**
         * 转换为 ERP 汇总表样式行，末行固定为服务端重算的合计行。
         *
         * @return 汇总行
         */
        private List<List<Object>> toRows() {
            List<List<Object>> rows = new ArrayList<>(groups.size() + 1);
            BigDecimal totalDebit = BigDecimal.ZERO;
            BigDecimal totalCredit = BigDecimal.ZERO;
            for (AccountGroup group : groups.values()) {
                rows.add(List.of(group.number, group.name,
                        plainAmount(group.debit), plainAmount(group.credit)));
                totalDebit = totalDebit.add(group.debit);
                totalCredit = totalCredit.add(group.credit);
            }
            rows.add(List.of("", "合计",
                    plainAmount(totalDebit), plainAmount(totalCredit)));
            return rows;
        }

        /**
         * 获取借方全局合计。
         *
         * @return 借方合计
         */
        private BigDecimal totalDebit() {
            return groups.values().stream().map(group -> group.debit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /**
         * 获取贷方全局合计。
         *
         * @return 贷方合计
         */
        private BigDecimal totalCredit() {
            return groups.values().stream().map(group -> group.credit)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /**
         * 获取科目分组，便于校验空结果和分组数量。
         *
         * @return 科目分组
         */
        private Map<String, AccountGroup> groups() {
            return groups;
        }
    }

    /**
     * 单个科目分组的聚合状态。
     */
    private static final class AccountGroup {

        /** 分组科目编码。 */
        private final String number;

        /** 科目名称，优先保留编码长度等于级别长度的分录名称。 */
        private String name = "";

        /** 是否已经观察到精确级别名称；该名称一旦出现不再被子级名称覆盖。 */
        private boolean exactNameObserved;

        /** 借方金额合计。 */
        private BigDecimal debit = BigDecimal.ZERO;

        /** 贷方金额合计。 */
        private BigDecimal credit = BigDecimal.ZERO;

        /**
         * 初始化科目分组。
         *
         * @param number 分组科目编码
         */
        private AccountGroup(String number) {
            this.number = number;
        }

        /**
         * 观察分录科目名称；精确级别名称优先，缺失时先被子级名称兜底。
         *
         * @param candidate 候选名称
         * @param exact 编码长度是否恰好等于级别长度
         */
        private void observeName(String candidate, boolean exact) {
            if (exact && !candidate.isBlank()) {
                if (!exactNameObserved) {
                    name = candidate;
                    exactNameObserved = true;
                }
                return;
            }
            if (name.isBlank() && !candidate.isBlank()) {
                name = candidate;
            }
        }
    }
}
