package com.customer.mcp.tool.publicmcp;

import com.customer.mcp.tool.publicmcp.k3.K3VoucherSummaryQueryTool;
import com.customer.mcp.k3.catalog.K3Catalog;
import com.customer.mcp.k3.catalog.K3FieldLocalizer;
import com.customer.mcp.k3.client.K3Gateway;
import com.customer.mcp.k3.client.K3Properties;
import com.customer.mcp.k3.errors.ErrorPatternCatalog;
import com.customer.mcp.k3.knowledge.KnowledgeEntry;
import com.customer.mcp.k3.knowledge.KnowledgeStore;
import com.kevin.mcp.execution.K3BusinessException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kevin.mcp.export.ExportProperties;
import com.kevin.mcp.export.ExportSessionRegistry;
import com.kevin.mcp.export.LargeResultExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证凭证汇总固定查询的参数校验、GL_Voucher_New 过滤组装、分页聚合和失败防护契约。
 *
 * @author Kevin
 * @date 2026-10-21
 */
class K3VoucherSummaryQueryToolTest {

    @TempDir
    Path directory;

    /**
     * 验证凭证字列表为空时直接拒绝，不调用金蝶。
     */
    @Test
    void rejectsEmptyVoucherGroups() {
        K3VoucherSummaryQueryTool tool = createTool(mock(K3Gateway.class), createExportService());
        assertThrows(IllegalArgumentException.class,
                () -> tool.queryVoucherSummary("和士美", "人民币", "2025-11-01",
                        "2025-11-30", "ALL", List.of(), null, null, null, null));
        verify(mock(K3Gateway.class), times(0)).query(anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyInt());
    }

    /**
     * 验证非法凭证范围、科目级别和日期顺序直接拒绝。
     */
    @Test
    void rejectsInvalidStructuredParameters() {
        K3VoucherSummaryQueryTool tool = createTool(mock(K3Gateway.class), createExportService());
        assertThrows(IllegalArgumentException.class,
                () -> tool.queryVoucherSummary("和士美", "人民币", "2025-11-01",
                        "2025-11-30", "SOMETING", List.of("收"), null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> tool.queryVoucherSummary("和士美", "人民币", "2025-11-01",
                        "2025-11-30", "ALL", List.of("收"), 4, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> tool.queryVoucherSummary("和士美", "人民币", "2025-11-30",
                        "2025-11-01", "ALL", List.of("收"), null, null, null, null));
    }

    /**
     * 验证凭证字号段被明确拒绝，不把未支持的组内号段当能力。
     */
    @Test
    void rejectsUnsupportedVoucherGroupRange() {
        K3VoucherSummaryQueryTool tool = createTool(mock(K3Gateway.class), createExportService());
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tool.queryVoucherSummary("和士美", "人民币", "2025-11-01",
                        "2025-11-30", "ALL", List.of("收:1-99"), null, null, null, null));
        assertTrue(exception.getMessage().contains("range is unsupported"));
    }

    /**
     * 验证编码账簿、已过账范围、科目级别和固定数据源进入 ExecuteBillQuery 过滤。
     */
    @Test
    void buildsQueryFilterWithStructuredConditions() {
        K3Gateway gateway = mock(K3Gateway.class);
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn(successResponse(List.of()));
        K3VoucherSummaryQueryTool tool = createTool(gateway, createExportService());

        tool.queryVoucherSummary("1000", "人民币", "2025-11-01", "2025-11-30",
                "POSTED", List.of("收"), 2, null, true, null);

        ArgumentCaptor<String> fields = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> order = ArgumentCaptor.forClass(String.class);
        verify(gateway).query(eq("GL_Voucher_New"), fields.capture(),
                filter.capture(), order.capture(), eq(0), eq(100));
        assertEquals("FACCOUNTID.FNumber,FACCOUNTID.FName,FACCOUNTID.FFullName,FDEBIT,FCREDIT",
                fields.getValue());
        assertEquals("FAccountBookID.FNumber='1000' and FDate>='2025-11-01'"
                        + " and FDate<='2025-11-30' and FCURRENCYID.FName='人民币'"
                        + " and FPOSTERID>0 and FVOUCHERGROUPID.FName='收'",
                filter.getValue());
        assertEquals("FACCOUNTID.FNumber", order.getValue());
        Map<String, Object> result = tool.queryVoucherSummary("1000", "人民币",
                "2025-11-01", "2025-11-30", "POSTED", List.of("收"), 2, null, true, null);
        assertTrue(result.get("summary").toString().contains("尚未校准调整期"));
    }

    /**
     * 验证 100 行分录触发第二页，且未过账条件直接翻译为 FPOSTERID 语义。
     */
    @Test
    void fetchesPagedEntryRows() {
        K3Gateway gateway = mock(K3Gateway.class);
        StringBuilder firstPageRows = new StringBuilder("[");
        for (int index = 0; index < 100; index++) {
            if (index > 0) {
                firstPageRows.append(',');
            }
            firstPageRows.append(row("100" + (index % 10), "科目", "科目全名",
                    String.valueOf(index), "0"));
        }
        firstPageRows.append(']');
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt()))
                .thenReturn(successJson(firstPageRows.toString()))
                .thenReturn(successResponse(List.of()));
        K3VoucherSummaryQueryTool tool = createTool(gateway, createExportService());

        var result = tool.queryVoucherSummary("和士美", "人民币", "2025-11-01",
                "2025-11-30", "UNPOSTED", List.of("转"), null, null, null, null);

        ArgumentCaptor<Integer> startRow = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(2)).query(eq("GL_Voucher_New"), anyString(),
                filter.capture(), anyString(), startRow.capture(), eq(100));
        assertEquals(0, startRow.getAllValues().get(0));
        assertEquals(100, startRow.getAllValues().get(1));
        assertEquals("FAccountBookID.FName='和士美' and FDate>='2025-11-01'"
                        + " and FDate<='2025-11-30' and FCURRENCYID.FName='人民币'"
                        + " and (FPOSTERID=0 or FPOSTERID is null) and FVOUCHERGROUPID.FName='转'",
                filter.getValue());
        assertEquals(10, ((List<?>) result.get("rows")).size() - 1);
    }

    /**
     * 验证名称账簿、默认级别、跨凭证字合并和精确级别名称优先。
     */
    @Test
    void usesBookNameDefaultsAndMergesGroups() {
        K3Gateway gateway = mock(K3Gateway.class);
        // 首页返回测试数据，空尾页终止分页；同一过滤重复调用时始终从首页重新开始。
        when(gateway.query(eq("GL_Voucher_New"), anyString(),
                contains("FVOUCHERGROUPID.FName='收'"), anyString(), anyInt(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(4, Integer.class) == 0
                        ? successResponse(List.of(
                                entry("1002", "银行存款一级", "资产.银行存款一级", "100", "20"),
                                entry("100201", "银行存款二级", "资产.银行存款二级", "10", "5")))
                        : successResponse(List.of()));
        when(gateway.query(eq("GL_Voucher_New"), anyString(),
                contains("FVOUCHERGROUPID.FName='付'"), anyString(), anyInt(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(4, Integer.class) == 0
                        ? successResponse(List.of(
                                entry("1002", "银行存款一级", "资产.银行存款一级", "1", "2")))
                        : successResponse(List.of()));
        K3VoucherSummaryQueryTool tool = createTool(gateway, createExportService());

        tool.queryVoucherSummary("和士美", "人民币", "2025/11/01", "2025/11/30",
                "ALL", List.of("收"), null, null, null, null);
        Map<String, Object> result = tool.queryVoucherSummary("和士美", "人民币",
                "2025/11/01", "2025/11/30", "ALL", List.of("收", "付"), 2, true, null, null);

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(3)).query(eq("GL_Voucher_New"), anyString(),
                filter.capture(), anyString(), anyInt(), anyInt());
        assertTrue(filter.getAllValues().get(0).contains("FAccountBookID.FName='和士美'"));
        List<?> rows = (List<?>) result.get("rows");
        // 级别 2 按编码前缀 7 截取；一级短编码和二级科目各自成组，末行重算合计。
        assertEquals(3, rows.size());
        assertEquals("1002", ((List<?>) rows.get(0)).get(0));
        assertEquals("资产.银行存款一级", ((List<?>) rows.get(0)).get(1));
        assertEquals("101", ((List<?>) rows.get(0)).get(2));
        assertEquals("22", ((List<?>) rows.get(0)).get(3));
        assertEquals("100201", ((List<?>) rows.get(1)).get(0));
        assertEquals("资产.银行存款二级", ((List<?>) rows.get(1)).get(1));
        assertEquals("10", ((List<?>) rows.get(1)).get(2));
        assertEquals("5", ((List<?>) rows.get(1)).get(3));
        assertEquals("111", ((List<?>) rows.get(2)).get(2));
        assertEquals("27", ((List<?>) rows.get(2)).get(3));
    }

    /**
     * 验证业务失败不学习表单知识，并进入失败记忆阻断原样重试。
     */
    @Test
    void doesNotLearnFailureAndRemembersSignature() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.resolve("knowledge-fail").toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn(
                "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":false,"
                        + "\"Errors\":[{\"Message\":\"元数据中标识为FACCOUNTID的字段不存在\"}]}}}");
        K3VoucherSummaryQueryTool tool = new K3VoucherSummaryQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), createExportService(), createLocalizer());

        assertThrows(K3BusinessException.class,
                () -> tool.queryVoucherSummary("和士美", "人民币", "2025-11-01",
                        "2025-11-30", "ALL", List.of("收"), null, null, null, null));

        assertNull(knowledge.get(KnowledgeEntry.Layer.ENTITY, "form:GL_Voucher_New"));
        assertNull(knowledge.get(KnowledgeEntry.Layer.ENTITY,
                "field:GL_Voucher_New:SUMMARY_ENTRY_FIELDS"));
        K3BusinessException repeat = assertThrows(K3BusinessException.class,
                () -> tool.queryVoucherSummary("和士美", "人民币", "2025-11-01",
                        "2025-11-30", "ALL", List.of("收"), null, null, null, null));
        assertTrue(repeat.getMessage().contains("该参数组合刚被金蝶拒绝"));
        assertTrue(repeat.getMessage().contains("核对"));
    }

    /**
     * 构造待测工具。
     *
     * @param gateway mock 金蝶网关
     * @param exportService 导出服务
     * @return 工具实例
     */
    private K3VoucherSummaryQueryTool createTool(K3Gateway gateway,
                                                 LargeResultExportService exportService) {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        return new K3VoucherSummaryQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), exportService, createLocalizer());
    }

    /**
     * 构造基于 mock 元数据的列头翻译器。
     *
     * @return 固定返回测试中文名的翻译器
     */
    private K3FieldLocalizer createLocalizer() {
        K3Catalog catalog = mock(K3Catalog.class);
        when(catalog.describe("GL_VOUCHERSUMMARY")).thenReturn(metadata());
        return new K3FieldLocalizer(catalog);
    }

    /**
     * 构造成功响应。
     *
     * @param rows 行对象列表
     * @return ExecuteBillQuery JSON
     */
    private static String successResponse(List<JsonObject> rows) {
        JsonArray array = new JsonArray();
        rows.forEach(array::add);
        return successJson(array.toString());
    }

    /**
     * 构造携带指定 Rows 数组 JSON 的成功响应。
     *
     * @param rowsJson Rows 数组 JSON
     * @return ExecuteBillQuery JSON
     */
    private static String successJson(String rowsJson) {
        return "{\"Result\":{\"ResponseStatus\":{\"IsSuccess\":true},\"Rows\":" + rowsJson + "}}";
    }

    /**
     * 构造一个分录对象行。
     *
     * @param number 科目编码
     * @param name 科目名称
     * @param fullName 科目全名
     * @param debit 借方
     * @param credit 贷方
     * @return 分录对象
     */
    private static JsonObject entry(String number, String name, String fullName,
                                    String debit, String credit) {
        JsonObject object = new JsonObject();
        object.addProperty("FACCOUNTID.FNumber", number);
        object.addProperty("FACCOUNTID.FName", name);
        object.addProperty("FACCOUNTID.FFullName", fullName);
        object.addProperty("FDEBIT", debit);
        object.addProperty("FCREDIT", credit);
        return object;
    }

    /**
     * 构造一个分录数组行。
     *
     * @param number 科目编码
     * @param name 科目名称
     * @param fullName 科目全名
     * @param debit 借方
     * @param credit 贷方
     * @return 分录数组 JSON 文本
     */
    private static String row(String number, String name, String fullName,
                              String debit, String credit) {
        JsonArray values = new JsonArray();
        values.add(number);
        values.add(name);
        values.add(fullName);
        values.add(debit);
        values.add(credit);
        return values.toString();
    }

    /**
     * 构造最小金蝶元数据，覆盖测试用字段中文名。
     *
     * @return 元数据 JSON
     */
    private JsonObject metadata() {
        JsonObject model = new JsonObject();
        model.add("Entrys", new JsonArray());
        JsonArray entrys = model.getAsJsonArray("Entrys");
        JsonObject entry = new JsonObject();
        entry.add("Fields", new JsonArray());
        JsonArray fields = entry.getAsJsonArray("Fields");
        for (String[] pair : new String[][]{
                {"FACCOUNTNUMBER", "科目编码"}, {"FACCOUNTNAME", "科目名称"},
                {"FDEBIT", "借方金额"}, {"FCREDIT", "贷方金额"}}) {
            fields.add(field(pair[0], pair[1]));
        }
        entrys.add(entry);
        JsonObject result = new JsonObject();
        result.add("NeedReturnData", model);
        JsonObject response = new JsonObject();
        response.add("Result", result);
        return response;
    }

    /**
     * 构造带简体中文名的字段节点。
     *
     * @param key 字段 Key
     * @param name 字段中文名
     * @return 字段 JSON
     */
    private JsonObject field(String key, String name) {
        JsonObject localized = new JsonObject();
        localized.addProperty("Key", 2052);
        localized.addProperty("Value", name);
        JsonObject node = new JsonObject();
        node.addProperty("Key", key);
        node.add("Name", new JsonArray());
        node.getAsJsonArray("Name").add(localized);
        return node;
    }

    /**
     * 构造临时目录导出服务，避免测试写污染真实导出目录。
     *
     * @return 可用的导出服务
     */
    private LargeResultExportService createExportService() {
        ExportProperties properties = new ExportProperties();
        properties.setExportDir(directory.toString());
        properties.setInlineMaxRows(100);
        properties.setInlineMaxBytes(64 * 1024);
        return new LargeResultExportService(properties, new ExportSessionRegistry());
    }
}


