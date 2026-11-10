package com.customer.mcp.tool.publicmcp;

import com.customer.mcp.tool.publicmcp.k3.K3AccountBookQueryTool;
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
import com.kevin.mcp.export.ExportProperties;
import com.kevin.mcp.export.ExportSessionRegistry;
import com.kevin.mcp.export.LargeResultExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证账簿固定查询的过滤组装、参数校验、调整期间降级和失败防护契约。
 *
 * @author Kevin
 * @date 2026-10-21
 */
class K3AccountBookQueryToolTest {

    @TempDir
    Path directory;

    /**
     * 验证 keyword 为空时全量查询，FilterString 为空串。
     */
    @Test
    void queriesAllBooksWithoutKeyword() {
        K3Gateway gateway = mock(K3Gateway.class);
        when(gateway.query(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("[]");
        K3AccountBookQueryTool tool = createTool(gateway, createExportService());

        tool.queryAccountBooks(null, null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).query(eq("BD_AccountBook"), captor.capture(), captor.capture(),
                captor.capture(), eq(0), eq(50));
        List<String> values = captor.getAllValues();
        assertEquals("", values.get(1));
        assertEquals("FNumber asc", values.get(2));
    }

    /**
     * 验证文本关键字走编码和名称双字段模糊匹配。
     */
    @Test
    void buildsFuzzyFilterForTextKeyword() {
        K3Gateway gateway = mock(K3Gateway.class);
        when(gateway.query(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("[]");
        K3AccountBookQueryTool tool = createTool(gateway, createExportService());

        tool.queryAccountBooks("士美", null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).query(anyString(), anyString(), captor.capture(),
                anyString(), anyInt(), anyInt());
        assertTrue(captor.getValue().contains("FNumber like '%士美%'"));
        assertTrue(captor.getValue().contains("FName like '%士美%'"));
        assertTrue(captor.getValue().contains(" or "));
    }

    /**
     * 验证纯数字关键字只按编码模糊匹配，不查名称。
     */
    @Test
    void buildsNumberFilterForNumericKeyword() {
        K3Gateway gateway = mock(K3Gateway.class);
        when(gateway.query(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("[]");
        K3AccountBookQueryTool tool = createTool(gateway, createExportService());

        tool.queryAccountBooks("014", null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gateway).query(anyString(), anyString(), captor.capture(),
                anyString(), anyInt(), anyInt());
        assertTrue(captor.getValue().contains("FNumber like '%014%'"));
        assertFalse(captor.getValue().contains("FName"));
    }

    /**
     * 验证 limit 超出 1 到 200 范围直接拒绝，不调用金蝶。
     */
    @Test
    void rejectsInvalidLimit() {
        K3AccountBookQueryTool tool = createTool(mock(K3Gateway.class), createExportService());
        assertThrows(IllegalArgumentException.class,
                () -> tool.queryAccountBooks(null, 0, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> tool.queryAccountBooks(null, 201, null, null));
    }

    /**
     * 验证 includeAdjustPeriod=true 时返回调整期间行和列头。
     */
    @Test
    void returnsAdjustPeriodsWhenRequested() {
        K3Gateway gateway = mock(K3Gateway.class);
        when(gateway.query(eq("BD_AccountBook"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn(
                "[[\"014\",\"和士美\",null,\"C\",\" \",\"100\",\"天津和士美\",\"PRE001\",\"人民币\",1,\"ACCT01\",\"会计科目01\",\"1\",\"1\",\"1\",11,2025,2025,1]]");
        when(gateway.query(eq("GL_AdjustPeriod"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn(
                "[[2025,12,\"1\",\"第12期调整\",\"年度调整\"]]");
        K3AccountBookQueryTool tool = createTool(gateway, createExportService());

        var result = tool.queryAccountBooks(null, null, true, null);

        assertEquals("inline", result.get("mode"));
        assertEquals(1, ((List<?>) result.get("rows")).size());
        assertNotNull(result.get("adjustPeriodFields"));
        assertEquals(1, ((List<?>) result.get("adjustPeriodRows")).size());
        assertNull(result.get("adjustPeriodWarning"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("bookCount"));
    }

    /**
     * 验证调整期间查询失败时主结果不失败，只返回警告引导自查。
     */
    @Test
    void degradesAdjustPeriodFailureToWarning() {
        K3Gateway gateway = mock(K3Gateway.class);
        when(gateway.query(eq("BD_AccountBook"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("[]");
        when(gateway.query(eq("GL_AdjustPeriod"), anyString(), anyString(),
                anyString(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("K3 metadata unavailable"));
        K3AccountBookQueryTool tool = createTool(gateway, createExportService());

        var result = tool.queryAccountBooks(null, null, true, null);

        assertEquals("inline", result.get("mode"));
        assertNull(result.get("adjustPeriodRows"));
        assertNotNull(result.get("adjustPeriodWarning"));
        assertTrue(((String) result.get("adjustPeriodWarning")).contains("GL_AdjustPeriod"));
    }

    /**
     * 验证内嵌金蝶错误不污染本地知识，并进入失败记忆阻断同参重试。
     */
    @Test
    void doesNotLearnEmbeddedErrorAndRemembersFailure() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.resolve("knowledge-fail").toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        when(gateway.query(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("""
                [[{"Result":{"ResponseStatus":{"IsSuccess":false,
                "Errors":[{"Message":"元数据中标识为FNumber的字段不存在"}]}}}]]
                """);
        K3AccountBookQueryTool tool = new K3AccountBookQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), createExportService(), createLocalizer());

        assertThrows(K3BusinessException.class,
                () -> tool.queryAccountBooks(null, null, null, null));

        assertNull(knowledge.get(KnowledgeEntry.Layer.ENTITY, "form:BD_AccountBook"));
        assertNull(knowledge.get(KnowledgeEntry.Layer.ENTITY, "field:BD_AccountBook:FIELDS"));
        K3BusinessException repeat = assertThrows(K3BusinessException.class,
                () -> tool.queryAccountBooks(null, null, null, null));
        assertTrue(repeat.getMessage().contains("该参数组合刚被金蝶拒绝"));
    }

    /**
     * 构造待测工具。
     *
     * @param gateway mock 金蝶网关
     * @param exportService 导出服务
     * @return 工具实例
     */
    private K3AccountBookQueryTool createTool(K3Gateway gateway,
                                              LargeResultExportService exportService) {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        return new K3AccountBookQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), exportService, createLocalizer());
    }

    /**
     * 构造基于 mock 元数据的列头翻译器。
     *
     * @return 固定返回测试中文名的翻译器
     */
    private K3FieldLocalizer createLocalizer() {
        K3Catalog catalog = mock(K3Catalog.class);
        when(catalog.describe("BD_AccountBook")).thenReturn(metadata());
        return new K3FieldLocalizer(catalog);
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
                {"FNumber", "编码"}, {"FName", "名称"}, {"FDescription", "描述"},
                {"FDocumentStatus", "数据状态"}, {"FForbidStatus", "禁用状态"},
                {"FUseOrgId", "使用组织"}, {"FCURRENCYID", "记账本位币"},
                {"FPeriodId", "会计日历"}, {"FACCTTABLEID", "科目表"},
                {"FBOOKTYPE", "账簿类型"}, {"FUSEADJUSTPERIOD", "启用调整期"},
                {"FINITIALSTATUS", "初始化状态"}, {"FCURRENTPERIOD", "当前期间"},
                {"FCURRENTYEAR", "当前年度"}, {"FSTARTYEAR", "启用年度"},
                {"FSTARTPERIOD", "启用期间"}}) {
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
     * @param name 简体中文名
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
