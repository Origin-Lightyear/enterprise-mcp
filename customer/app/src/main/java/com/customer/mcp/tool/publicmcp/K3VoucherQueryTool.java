package com.customer.mcp.tool.publicmcp;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 固定查询总账凭证（GL_Voucher_New），是 {@code k3_query_data} 的领域化只读薄封装。
 *
 * <p>FormId 固定为 {@code GL_Voucher_New}，每次返回与 ERP 凭证界面一致的完整字段：
 * 单据头全字段展开为凭证级数据，单据体分录字段展开为分录行数据。返回结果同时给出
 * {@code voucherCount}（按 FBillNo 去重后的凭证张数）和 {@code entryCount}（分录行数），
 * 避免把分录行数误报成凭证张数。分页与排序都作用于分录行粒度，与金蝶
 * ExecuteBillQuery 的真实行为保持一致。该工具不解决“用户说的凭证到底是哪张表单”
 * 的语义问题；账簿编码、年度、期间、凭证字或凭证号不确定时，Agent 应先确认或回退到
 * {@code k3_search_forms} / {@code k3_describe_form}，不能凭空猜测。</p>
 *
 * @author Kevin
 * @date 2026-10-16
 */
@Component
@ConditionalOnProperty(name = "enterprise.k3.enabled", havingValue = "true")
public class K3VoucherQueryTool {

    /** 失败参数记忆有效期；超时后允许 Agent 携带修正参数重新验证。 */
    private static final long FAILURE_MEMORY_TTL_MS = 10L * 60 * 1000;

    /**
     * 对齐 ERP 凭证列表展示顺序的全字段清单；lookup 字段统一请求 .FName 显示名，
     * 枚举字段保留本体码值，由现场元数据字典在展示层翻译。
     * 前段是 ERP 截图可见列，后段是补充明细字段；删除 FSourceBillKey 和 FDETAILID
     * 内部 ID。用户可感知的凭证号是凭证字加组内序号，FBillNo 是系统内部单据编号。
     */
    private static final List<String> FIELD_LIST = List.of(
            "FDATE", "FYEAR", "FPERIOD", "FVOUCHERGROUPID.FName", "FVOUCHERGROUPNO",
            "FEXPLANATION", "FACCOUNTID.FNumber", "FACCOUNTID.FName",
            "FCURRENCYID.FName", "FAMOUNTFOR", "FDEBIT", "FCREDIT",
            "FCreatorId.FName", "FCHECKERID.FName", "FPOSTERID.FName", "FCASHIERID.FName",
            "FATTACHMENTS", "FSystemID.FName", "FDocumentStatus", "FINVALID",
            "FBillNo", "FBUSDATE", "FAccountBookID.FNumber", "FAccountBookID.FName",
            "FACCBOOKORGID.FNumber", "FACCBOOKORGID.FName",
            "FDEBITTOTAL", "FCREDITTOTAL", "FPOSTED", "FISCASHFLOW",
            "FIsSplit", "FDepositOut", "FDepositIn", "FRemarks",
            "FCreateDate", "FModifierId.FName", "FModifyDate",
            "FAuditDate", "FBASECURRENCYID.FName",
            "FEXCHANGERATETYPE.FName", "FEXCHANGERATE",
            "FUNITID.FName", "FPRICE", "FQTY", "FACCTUNITQTY", "FBASEUNITQTY",
            "FSETTLETYPEID.FName", "FSETTLENO", "FBUSNO", "FISMULTICOLLECT");

    /** 请求 FieldKeys 串；顺序即返回列顺序。 */
    private static final String ALL_FIELDS = String.join(",", FIELD_LIST);

    /** 凭证级视图字段清单；对应 ERP 凭证列表界面可见的表头列。 */
    private static final List<String> HEADER_FIELDS = List.of(
            "FBillNo", "FVOUCHERGROUPID.FName", "FVOUCHERGROUPNO",
            "FDATE", "FPERIOD",
            "FAccountBookID.FNumber", "FAccountBookID.FName",
            "FDEBITTOTAL", "FCREDITTOTAL",
            "FDocumentStatus", "FPOSTED",
            "FCreatorId.FName", "FCHECKERID.FName", "FAuditDate", "FRemarks");

    /** 凭证级视图请求 FieldKeys 串。 */
    private static final String HEADER_FIELD_KEYS = String.join(",", HEADER_FIELDS);

    /**
     * 凭证级单据头字段 Key 集合；只有这些字段按凭证分组，分录字段每行必须保留。
     */
    private static final Set<String> HEADER_FIELD_KEYS_SET = Set.of(
            "FDATE", "FYEAR", "FPERIOD", "FVOUCHERGROUPID.FName", "FVOUCHERGROUPNO",
            "FBillNo", "FBUSDATE", "FAccountBookID.FNumber", "FAccountBookID.FName",
            "FACCBOOKORGID.FNumber", "FACCBOOKORGID.FName",
            "FDEBITTOTAL", "FCREDITTOTAL", "FDocumentStatus", "FPOSTED",
            "FINVALID", "FISCASHFLOW", "FSystemID.FName",
            "FIsSplit", "FDepositOut", "FDepositIn", "FRemarks",
            "FCreateDate", "FModifierId.FName", "FModifyDate", "FAuditDate",
            "FBASECURRENCYID.FName", "FATTACHMENTS");

    private final K3Gateway gateway;
    private final KnowledgeStore knowledge;
    private final ErrorPatternCatalog errorPatterns;
    private final LargeResultExportService exportService;
    /** 现场元数据列头翻译器，保证用户看到中文名而不是内部字段 Key。 */
    private final K3FieldLocalizer fieldLocalizer;
    /** 记录最近被金蝶明确拒绝的参数签名；同参快速失败能打断 Agent 盲猜循环。 */
    private final ConcurrentHashMap<String, FailedCall> recentFailures = new ConcurrentHashMap<>();

    /**
     * 注入金蝶查询依赖。
     *
     * @param gateway 金蝶 SDK 网关
     * @param knowledge 客户本地知识
     * @param errorPatterns 金蝶错误模式目录
     * @param exportService 大结果统一导出服务
     * @param fieldLocalizer 现场元数据列头翻译器
     */
    public K3VoucherQueryTool(K3Gateway gateway, KnowledgeStore knowledge,
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
     * 按账簿、年度、期间、凭证字或凭证号查询总账凭证，返回 ERP 可见的全字段分录展开视图。
     *
     * <p>过滤表达式是金蝶原生 FilterString 而非 SQL，不支持 GETDATE/CONVERT/DATEADD
     * 等数据库函数。常用过滤字段：账簿用 {@code FAccountBookID.FNumber}，年度用
     * {@code FYEAR}，期间用 {@code FPERIOD}，凭证字名称用 {@code FVOUCHERGROUPID.FName}，
     * 组内凭证号用 {@code FVOUCHERGROUPNO}；{@code FBillNo} 是系统内部凭证编号，与 ERP
     * 界面的凭证号不同。分录行中的凭证头列在每行重复，按凭证头列去重即可得到凭证张数。</p>
     *
     * @param filterString 金蝶原生过滤表达式，可为空
     * @param order 金蝶排序表达式，为空时按凭证日期和凭证编号降序
     * @param startRow 分页起点，从 0 开始，作用于分录行粒度
     * @param limit 单页行数，最大 100
     * @param view 视图模式：full（默认，必须保持）返回 50 字段分录展开；仅用户明确要求精简时才传 header 返回凭证级表头视图
     * @param requestContext MCP 请求上下文，由安全切面校验
     * @return MCP 返回体：inline 携带明细行，file 携带下载引用，模式无关字段不会为 null
     */
    @McpTool(name = "k3_query_vouchers",
            description = "金蝶固定工具：当用户意图是总账凭证、凭证列表或凭证分录，"
                    + "或 k3_search_forms 候选中出现 GL_Voucher_New 时，直接调用本工具，"
                    + "不要再用 k3_describe_form 探测或 k3_query_data 手工拼查询。"
                    + "固定返回 ERP 凭证界面可见的全字段分录展开视图：voucherCount 是按"
                    + " FBillNo 去重后的凭证张数，entryCount 是分录行数，hasMore 与分页"
                    + "作用于分录行粒度。filterString 是金蝶表达式而非 SQL；账簿用"
                    + " FAccountBookID.FNumber，年度用 FYEAR，期间用 FPERIOD；ERP 凭证号"
                    + "是凭证字 FVOUCHERGROUPID.FName 加组内序号 FVOUCHERGROUPNO，"
                    + "FBillNo 是系统内部编号。默认必须保持 view=full 全字段分录展开；仅用户明确要求凭证级精简清单时才传 view=header，"
                    + "看凭证分录明细用默认 view=full。返回 mode=inline 时 rows 是明细行；"
                    + "返回 mode=file 时明细已在服务器 CSV 中，必须立即使用 downloadUrl 下载并保存给用户，"
                    + "totalCount 是服务端流式统计的真实总行数，不要把 file 模式当成未完成查询。关键条件不确定时先向用户确认。",
            generateOutputSchema = true)
    @PublicMcpTool(capabilityId = "k3.query-vouchers", description = "查询总账凭证单据和分录")
    public Map<String, Object> queryVouchers(
            @McpToolParam(description = "金蝶过滤表达式，可为空", required = false) String filterString,
            @McpToolParam(description = "金蝶排序表达式，为空时按凭证日期和凭证编号降序", required = false) String order,
            @McpToolParam(description = "分页起点，从 0 开始，作用于分录行", required = false) Integer startRow,
            @McpToolParam(description = "单页行数，最大 100", required = false) Integer limit,
            @McpToolParam(description = "视图模式：默认 full 全字段分录展开，必须保持；仅用户明确要求凭证级精简清单时才传 header", required = false) String view,
            McpSyncRequestContext requestContext) {

        String effectiveFilter = filterString == null ? "" : filterString.trim();
        rejectRepeatedFailure(effectiveFilter, order, startRow, limit);
        if (effectiveFilter.length() > 2000) {
            throw new IllegalArgumentException("K3 voucher filter is too long");
        }
        int requestedRow = startRow == null ? 0 : startRow;
        int requestedSize = limit == null ? 20 : limit;
        if (requestedRow < 0 || requestedSize < 1 || requestedSize > 100) {
            throw new IllegalArgumentException("K3 voucher pagination is invalid");
        }
        String effectiveOrder = order == null || order.isBlank()
                ? "FDate desc,FBillNo desc" : order.trim();
        boolean headerView = "header".equalsIgnoreCase(view);
        String fieldKeys = headerView ? HEADER_FIELD_KEYS : ALL_FIELDS;
        List<String> fieldNames = fieldLocalizer.fieldNames("GL_Voucher_New", fieldKeys);
        List<String> fieldKeysList = List.of(fieldKeys.split(","));
        // 枚举字典来自 QueryBusinessInfo 现场元数据，账套修改枚举后展示自动跟随。
        Map<String, Map<String, String>> enumDictionaries =
                fieldLocalizer.enumDictionaries("GL_Voucher_New");

        // 第一页先按用户分页参数取回，用于判断是否已在阈值内；超过阈值才切换流式导出。
        List<List<Object>> firstPage = toDisplayRows(fetchPage(fieldKeys, effectiveFilter,
                effectiveOrder, requestedRow, requestedSize), fieldKeysList, enumDictionaries);
        if (!headerView) {
            firstPage = groupHeaderFields(firstPage, fieldKeysList);
        }
        List<List<Object>> inlineRows = new ArrayList<>(firstPage);
        int estimateBytes = estimateSerializedBytes(firstPage);
        if (exportService.isInline(inlineRows.size(), estimateBytes)) {
            // 只有结果能成功解析时才把 FormId 和全字段学习为有效，防止错误响应污染本地知识。
            knowledge.observe(KnowledgeEntry.Layer.ENTITY, "form:GL_Voucher_New",
                    "present", 30L * 24 * 3600);
            knowledge.observe(KnowledgeEntry.Layer.ENTITY,
                    "field:GL_Voucher_New:" + (headerView ? "HEADER_FIELDS" : "ALL_FIELDS"),
                    "valid", 30L * 24 * 3600);
            return toMcpResult(ExportedQueryResult.inline("GL_Voucher_New", fieldNames, inlineRows,
                    firstPage.size() == requestedSize,
                    buildInlineSummary(fieldNames, inlineRows)));
        }

        // 大结果转为流式导出：继续从分页起点拉全量，内存只保留阈值加一页。
        LargeResultExportService.StreamingExport export =
                exportService.beginExport("GL_Voucher_New", fieldNames);
        try {
            for (List<Object> row : inlineRows) {
                exportService.appendRow(export, row);
            }
            int pageSize = 100;
            int cursor = requestedRow + inlineRows.size();
            while (true) {
                List<List<Object>> page = toDisplayRows(fetchPage(fieldKeys, effectiveFilter,
                        effectiveOrder, cursor, pageSize), fieldKeysList, enumDictionaries);
                if (!headerView) {
                    page = groupHeaderFields(page, fieldKeysList);
                }
                if (page.isEmpty()) {
                    break;
                }
                for (List<Object> row : page) {
                    exportService.appendRow(export, row);
                }
                cursor += page.size();
                if (page.size() < pageSize) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            exportService.cancelExport(export);
            throw exception;
        }
        knowledge.observe(KnowledgeEntry.Layer.ENTITY, "form:GL_Voucher_New",
                "present", 30L * 24 * 3600);
        knowledge.observe(KnowledgeEntry.Layer.ENTITY,
                "field:GL_Voucher_New:" + (headerView ? "HEADER_FIELDS" : "ALL_FIELDS"),
                "valid", 30L * 24 * 3600);
        return toMcpResult(exportService.finishExport(export));
    }

    /**
     * 把统一查询结果转换为 MCP 最终返回体。
     *
     * <p>Spring AI 会把 record 组件全部生成为 required 输出字段，inline 模式的
     * file 专属字段为 null 时会被客户端 schema 校验拒收。这里按模式裁剪字段，
     * 不携带 null 占位；Map 返回也避免固定 required 契约与两种模式互相冲突。</p>
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
     * 按单据编号把单据头字段限制在每张凭证第一条分录显示。
     *
     * <p>ERP 界面用主从表格表达该规则；CSV 平面结构只能用空串隐藏后续重复头值，
     * 保留原始顺序可继续支持分页流式导出。分组状态只在一批连续页内重算，
     * 排序保证同一凭证行连续。</p>
     *
     * @param rows 展示行
     * @param fieldKeys 原始字段 Key
     * @param billNoIndex 单据编号列下标
     * @param headerCount 单据头字段数量
     * @return 后续分录头字段置空的展示行
     */
    private static List<List<Object>> groupHeaderFields(List<List<Object>> rows,
                                                        List<String> fieldKeys) {
        int billNoIndex = fieldKeys.indexOf("FBillNo");
        if (billNoIndex < 0) {
            return rows;
        }
        List<Integer> headerIndexes = new ArrayList<>();
        for (int index = 0; index < fieldKeys.size(); index++) {
            if (HEADER_FIELD_KEYS_SET.contains(fieldKeys.get(index))) {
                headerIndexes.add(index);
            }
        }
        String currentBillNo = null;
        for (List<Object> row : rows) {
            if (billNoIndex >= row.size()) {
                continue;
            }
            Object billNo = row.get(billNoIndex);
            if (!java.util.Objects.equals(billNo, currentBillNo)) {
                currentBillNo = billNo == null ? null : String.valueOf(billNo);
                continue;
            }
            for (Integer index : headerIndexes) {
                if (index < row.size()) {
                    row.set(index, "");
                }
            }
        }
        return rows;
    }

    /**
     * 调用金蝶分页接口并把原始二维数组转换为纯 Java 行对象；解析失败直接抛出。
     *
     * @param fieldKeys 当前请求的字段 Key
     * @param filter 当前过滤表达式
     * @param order 当前排序表达式
     * @param row 分页起点
     * @param size 单页行数
     * @return 纯 Java 行列表
     */
    private List<List<Object>> fetchPage(String fieldKeys, String filter, String order,
                                         int row, int size) {
        String raw = gateway.query("GL_Voucher_New", fieldKeys, filter, order, row, size);
        JsonElement parsed = JsonParser.parseString(raw);
        JsonArray rows = parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        if (rows == null) {
            throw new IllegalStateException("K3 voucher response is not a row array");
        }
        rejectEmbeddedQueryErrors(rows, fieldKeys, filter);
        List<List<Object>> result = new ArrayList<>(rows.size());
        for (JsonElement rowElement : rows) {
            if (rowElement.isJsonArray()) {
                result.add((List<Object>) toPlainObject(rowElement));
            }
        }
        return result;
    }

    /**
     * 把金蝶原始行转换成用户可读展示行。
     *
     * <p>普通布尔字段统一翻译为是/否，金蝶日期只保留日期部分并使用 ERP 风格斜杠；
     * 完整时间字段保留到分钟。枚举字段用现场元数据字典翻译，不做硬编码映射，
     * 未知码值保留原码；lookup 字段由 .FName 返回中文名，金额字符串统一规整末尾零。</p>
     *
     * @param rows 金蝶原始行
     * @param fieldKeys 请求字段 Key，顺序与行一致
     * @param enumDictionaries 现场枚举字典
     * @return 展示行
     */
    private static List<List<Object>> toDisplayRows(List<List<Object>> rows,
                                                    List<String> fieldKeys,
                                                    Map<String, Map<String, String>> enumDictionaries) {
        List<List<Object>> result = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> display = new ArrayList<>(row.size());
            for (int index = 0; index < row.size() && index < fieldKeys.size(); index++) {
                display.add(toDisplayValue(fieldKeys.get(index), row.get(index), enumDictionaries));
            }
            result.add(display);
        }
        return result;
    }

    /**
     * 转换单元格展示值。
     *
     * @param fieldKey 字段 Key
     * @param value 原始值
     * @param enumDictionaries 现场枚举字典
     * @return 用户可读值
     */
    private static Object toDisplayValue(String fieldKey, Object value,
                                         Map<String, Map<String, String>> enumDictionaries) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean bool) {
            return bool ? "是" : "否";
        }
        // 枚举翻译优先于其他转换，码值字典以 QueryBusinessInfo 返回的 Extends 为准。
        Map<String, String> enumValues = enumDictionaries.get(fieldKey);
        if (enumValues != null && value instanceof String code) {
            return enumValues.getOrDefault(code, code);
        }
        if (value instanceof String text && !text.isBlank()
                && (fieldKey.equals("FDATE") || fieldKey.equals("FBUSDATE")
                || fieldKey.equals("FAuditDate") || fieldKey.equals("FCreateDate")
                || fieldKey.equals("FModifyDate"))) {
            if (text.length() >= 10 && text.charAt(4) == '-' && text.charAt(7) == '-') {
                LocalDate date = LocalDate.parse(text.substring(0, 10));
                return date.format(DateTimeFormatter.ofPattern("yyyy/M/d"));
            }
            return text;
        }
        // 金蝶 decimal 字符串常带补零尾巴；统一去掉无意义零，保持 CSV 可读且可继续解析。
        if (value instanceof String text && !text.isBlank()
                && text.matches("-?\\d+\\.\\d+")) {
            return new java.math.BigDecimal(text).stripTrailingZeros().toPlainString();
        }
        return value;
    }

    /**
     * 估算行列表的序列化字节数；按字符串 UTF-16 长度近似，避免完整序列化开销。
     *
     * @param rows 行列表
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
     * 为 inline 模式生成轻量列画像摘要；与 file 模式协议保持一致。
     *
     * @param fields 字段名列表
     * @param rows 行数据
     * @return 列画像映射
     */
    private static Map<String, Object> buildInlineSummary(List<String> fields,
                                                          List<List<Object>> rows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            long numericCount = 0;
            double numericSum = 0;
            double numericMin = Double.MAX_VALUE;
            double numericMax = -Double.MAX_VALUE;
            Map<String, Long> distribution = new LinkedHashMap<>();
            boolean overflow = false;
            for (List<Object> row : rows) {
                if (index >= row.size()) {
                    continue;
                }
                Object value = row.get(index);
                if (value instanceof Number number) {
                    numericCount++;
                    double doubleValue = number.doubleValue();
                    numericSum += doubleValue;
                    numericMin = Math.min(numericMin, doubleValue);
                    numericMax = Math.max(numericMax, doubleValue);
                } else if (value != null && !String.valueOf(value).isBlank()) {
                    // 空串是单据头分组的布局占位，不能进入取值分布误导图表生成。
                    String text = String.valueOf(value);
                    if (!overflow) {
                        distribution.merge(text, 1L, Long::sum);
                        if (distribution.size() > 20) {
                            distribution.clear();
                            overflow = true;
                        }
                    }
                }
            }
            Map<String, Object> column = new LinkedHashMap<>();
            if (numericCount > 0) {
                column.put("numericCount", numericCount);
                column.put("sum", Math.round(numericSum * 100.0) / 100.0);
                column.put("min", Math.round(numericMin * 100.0) / 100.0);
                column.put("max", Math.round(numericMax * 100.0) / 100.0);
                column.put("avg", Math.round(numericSum / numericCount * 100.0) / 100.0);
            }
            if (overflow) {
                column.put("distribution", "HIGH_CARDINALITY");
            } else if (!distribution.isEmpty()) {
                column.put("distribution", Map.copyOf(distribution));
            }
            if (!column.isEmpty()) {
                summary.put(fields.get(index), column);
            }
        }
        return summary;
    }

    /**
     * 拒绝金蝶在 ExecuteBillQuery 响应内嵌的业务错误，防止把错误行当数据学习。
     *
     * @param rows 金蝶返回的二维查询结果
     * @param fieldKeys 本次请求的字段 Key
     * @param filterString 当前过滤表达式
     */
    private void rejectEmbeddedQueryErrors(JsonArray rows, String fieldKeys, String filterString) {
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
            recordFailure(fieldKeys, filterString, status);
            throw new K3BusinessException(toBusinessExceptionAdvice(status));
        }
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
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsNumber();
            }
            return element.getAsString();
        }
        return element.getAsString();
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
    private String describeResponseStatusErrors(JsonObject status) {
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
            String text = List.of(field, message, description).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .collect(Collectors.joining(": "));
            if (!text.isBlank() && !errors.contains(text)) {
                errors.add(text);
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
     * 记录金蝶明确业务失败的参数签名和有界修正建议。
     *
     * @param fieldKeys 当前字段 Key
     * @param filterSignature 当前过滤签名
     * @param status 金蝶 ResponseStatus 对象
     */
    private void recordFailure(String fieldKeys, String filterSignature, JsonObject status) {
        if (status == null) {
            throw new IllegalArgumentException("K3 failure status is required");
        }
        // 只保留少量最近失败，避免长驻进程积累；建议文本已在错误匹配阶段做有界截断。
        if (recentFailures.size() > 128) {
            recentFailures.clear();
        }
        recentFailures.put("GL_Voucher_New|" + fieldKeys + '|' + filterSignature
                        + '|' + signature(status),
                new FailedCall(System.currentTimeMillis(), toBusinessExceptionAdvice(status)));
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

    /** 保留最近失败调用的参数签名和建议，仅用于打断重复盲猜。 */
    private record FailedCall(long at, String advice) {
    }
}
