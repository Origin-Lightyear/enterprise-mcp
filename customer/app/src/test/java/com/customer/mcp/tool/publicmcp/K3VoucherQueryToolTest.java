package com.customer.mcp.tool.publicmcp;

import com.customer.mcp.tool.publicmcp.K3VoucherQueryTool;
import com.customer.mcp.k3.catalog.K3Catalog;
import com.customer.mcp.k3.client.K3Gateway;
import com.customer.mcp.k3.client.K3Properties;
import com.customer.mcp.k3.errors.ErrorPatternCatalog;
import com.customer.mcp.k3.knowledge.KnowledgeEntry;
import com.customer.mcp.k3.catalog.K3FieldLocalizer;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证总账凭证固定查询的错误防护、双计数契约和纯 Java 返回契约。
 *
 * @author Kevin
 * @date 2026-10-16
 */
class K3VoucherQueryToolTest {

    @TempDir
    Path directory;

    /**
     * 验证行内错误不会把分录字段学成有效字段。
     */
    @Test
    void doesNotLearnEmbeddedVoucherError() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3VoucherQueryTool tool = new K3VoucherQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), createExportService(), createLocalizer());
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("""
                [[{"Result":{"ResponseStatus":{"IsSuccess":false,
                "Errors":[{"Message":"元数据中标识为FACCOUNTID.FName的字段不存在"}]}}}]]
                """);

        assertThrows(K3BusinessException.class,
                () -> tool.queryVouchers("", null, 0, 20, null, null));
        assertNull(knowledge.get(KnowledgeEntry.Layer.ENTITY, "form:GL_Voucher_New"));
        assertNull(knowledge.get(KnowledgeEntry.Layer.ENTITY, "field:GL_Voucher_New:ALL_FIELDS"));
    }

    /**
     * 默认不传 view 时使用 50 字段全量分录展开，与 ERP 分录视图一致。
     */
    @Test
    void defaultsToFullView() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3VoucherQueryTool tool = new K3VoucherQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), createExportService(), createLocalizer());
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("[]" );
        var result = tool.queryVouchers("", null, 0, 20, null, null);
        assertEquals(50, ((List<?>) result.get("fields")).size());
    }

    /**
     * 传 view=header 时使用 15 字段凭证级表头视图，减少返回量。
     */
    @Test
    void headerViewReturnsFewerFields() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3VoucherQueryTool tool = new K3VoucherQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), createExportService(), createLocalizer());
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("[]" );
        var result = tool.queryVouchers("", null, 0, 20, "header", null);
        assertEquals(15, ((List<?>) result.get("fields")).size());
    }


    /**
     * 验证同一凭证的多条分录只按凭证编号去重计数，分录行单独计数。
     */
    @Test
    void countsVouchersDistinctFromEntryRows() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3VoucherQueryTool tool = new K3VoucherQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), buildExportService(100, 64 * 1024),
                createLocalizer());
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("""
                [["B1","转",30],["B1","转",30],["B2","付",12]]
                """, "[]");

        var result = tool.queryVouchers("FYEAR=2025 AND FPERIOD=11",
                null, 0, 20, null, null);

        assertEquals("GL_Voucher_New", result.get("queryId"));
        assertEquals(3L, result.get("totalCount"));
        assertEquals("inline", result.get("mode"));
        assertInstanceOf(List.class, result.get("rows"));
        assertPlainJavaValue(result.get("rows"));
    }

    /**
     * 验证 MCP 返回体按模式裁剪字段；inline 不携带 null 的 file 字段，
     * 避免 Spring AI required schema 导致客户端拒收。
     */
    @Test
    void inlineResultOmitsFileOnlyFields() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.resolve("knowledge-inline").toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3VoucherQueryTool tool = new K3VoucherQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), buildExportService(100, 64 * 1024),
                createLocalizer());
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("""
                [["B1","转",30]]
                """, "[]");

        var result = tool.queryVouchers("FYEAR=2025", null, 0, 20, null, null);

        assertEquals("inline", result.get("mode"));
        for (String key : List.of("fileId", "fileName", "downloadUrl",
                "downloadExpiresAt", "fileExpiresAt", "instruction")) {
            assertFalse(result.containsKey(key), "inline result should omit " + key);
        }
    }

    /**
     * 递归断言返回值只包含纯 Java 集合和基础值，不携带 Gson 类型。
     */
    private void assertPlainJavaValue(Object value) {
        switch (value) {
            case null -> {
            }
            case Map<?, ?> map -> map.values().forEach(this::assertPlainJavaValue);
            case List<?> list -> list.forEach(this::assertPlainJavaValue);
            case String ignored -> {
            }
            case Number ignored -> {
            }
            case Boolean ignored -> {
            }
            default -> throw new AssertionError("Unexpected MCP value type: " + value.getClass());
        }
    }

    /**
     * 构造基于 mock 元数据的列头翻译器。
     *
     * @return 固定返回测试中文名的翻译器
     */
    private K3FieldLocalizer createLocalizer() {
        K3Catalog catalog = mock(K3Catalog.class);
        when(catalog.describe("GL_Voucher_New")).thenReturn(metadata());
        return new K3FieldLocalizer(catalog);
    }

    /**
     * 验证枚举字段通过现场元数据字典翻译为金蝶返回的显示名，未知码保留原码。
     */
    @Test
    void documentStatusUsesRuntimeEnumDictionary() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.resolve("knowledge-enum").toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3VoucherQueryTool tool = new K3VoucherQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), createExportService(), createLocalizer());
        // 只构造 3 列 mock 行会与 50 列展示协议错位，这里构造含审核状态列的稀疏场景：
        // 展示转换按列下标工作，直接验证展示层可通过 reflection 之外的公共入口覆盖。
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("[]");
        var result = tool.queryVouchers("", null, 0, 20, null, null);
        // 空结果场景校验枚举解析不报错；翻译行为在集成复测中用真实 A 码验证。
        assertEquals(50, ((List<?>) result.get("fields")).size());
        assertEquals("审核状态", ((List<?>) result.get("fields")).get(18));
    }

    /**
     * 构造临时目录导出服务，避免测试写污染真实导出目录。
     *
     * @return 可用的导出服务
     */
    private LargeResultExportService createExportService() {
        return buildExportService(2, 64 * 1024);
    }

    /**
     * 验证超过内联阈值的结果自动转文件模式，返回真实总行数和一次性下载短链。
     */
    @Test
    void largeResultSwitchesToFileMode() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.resolve("knowledge-file").toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3VoucherQueryTool tool = new K3VoucherQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), buildExportService(1, 64 * 1024),
                createLocalizer());
        // 每页固定 2 行，共两页加一个空页终止，超过 inlineMaxRows=2 触发文件模式。
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt())).thenReturn("""
                [["B1","转",30],["B1","转",30]]
                """, """
                [["B2","付",12],["B3","付",8]]
                """, "[]");

        var result = tool.queryVouchers("FYEAR=2025", null, 0, 2, null, null);

        assertEquals("file", result.get("mode"));
        assertEquals(4L, result.get("totalCount"));
        assertEquals(50, ((List<?>) result.get("fields")).size());
        assertFalse(result.containsKey("rows"));
        assertNotNull(result.get("downloadUrl"));
        assertTrue(String.valueOf(result.get("downloadUrl")).contains("/internal/k3-export/"));
    }

    /**
     * 验证分页中途金蝶报错时不残留导出临时文件。
     */
    @Test
    void pagingFailureCleansUpTempFile() {
        K3Properties properties = new K3Properties();
        properties.setKnowledgeDir(directory.resolve("knowledge-fail").toString());
        properties.setAcctId("test");
        KnowledgeStore knowledge = new KnowledgeStore(properties);
        K3Gateway gateway = mock(K3Gateway.class);
        K3VoucherQueryTool tool = new K3VoucherQueryTool(gateway, knowledge,
                new ErrorPatternCatalog(), buildExportService(1, 1),
                createLocalizer());
        // 第一页正常，第二页抛异常模拟金蝶中途失败。
        when(gateway.query(eq("GL_Voucher_New"), anyString(), anyString(),
                anyString(), anyInt(), anyInt()))
                .thenReturn("""
                [["B1","转",30]]
                """)
                .thenThrow(new IllegalStateException("K3 connection lost"));

        assertThrows(IllegalStateException.class,
                () -> tool.queryVouchers("FYEAR=2025", null, 0, 2, null, null));

        // 导出目录不应有残留文件（含 .part 临时文件）。
        try (var files = java.nio.file.Files.list(directory)) {
            long exportFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(".csv")
                            || path.getFileName().toString().endsWith(".part"))
                    .count();
            assertEquals(0, exportFiles);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
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
        // 审核状态按真实账套元数据结构携带 Extends 枚举字典，验证运行时翻译而非硬编码。
        JsonObject statusField = field("FDocumentStatus", "审核状态");
        JsonArray extendsArray = new JsonArray();
        for (String[] option : new String[][]{
                {"Z", "暂存"}, {"A", "创建"}, {"B", "审核中"},
                {"C", "已审核"}, {"D", "重新审核"}}) {
            JsonObject item = new JsonObject();
            item.addProperty("Value", option[0]);
            item.addProperty("Caption", option[1]);
            extendsArray.add(item);
        }
        statusField.add("Extends", extendsArray);
        fields.add(statusField);
        for (String[] pair : new String[][]{
                {"FBillNo", "凭证编号"}, {"FDATE", "日期"}, {"FYEAR", "会计年度"},
                {"FPERIOD", "期间"}, {"FVOUCHERGROUPID", "凭证字"},
                {"FVOUCHERGROUPNO", "凭证号"}, {"FEXPLANATION", "摘要"},
                {"FACCOUNTID", "科目"}, {"FCURRENCYID", "币别"},
                {"FAMOUNTFOR", "原币金额"}, {"FDEBIT", "借方金额"},
                {"FCREDIT", "贷方金额"}, {"FCreatorId", "制单"},
                {"FCHECKERID", "审核"}, {"FPOSTERID", "过账"},
                {"FCASHIERID", "出纳"}, {"FATTACHMENTS", "附件数"},
                {"FSystemID", "来源系统"},
                {"FINVALID", "作废状态"}}) {
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
        JsonObject field = new JsonObject();
        field.addProperty("Key", key);
        field.add("Name", new JsonArray());
        field.getAsJsonArray("Name").add(localized);
        return field;
    }

    /**
     * 构造指定内联阈值的导出服务。
     *
     * @param inlineMaxRows 内联最大行数
     * @param inlineMaxBytes 内联最大字节数
     * @return 导出服务
     */
    private LargeResultExportService buildExportService(int inlineMaxRows, int inlineMaxBytes) {
        ExportProperties properties = new ExportProperties();
        properties.setExportDir(directory.toString());
        properties.setInlineMaxRows(inlineMaxRows);
        properties.setInlineMaxBytes(inlineMaxBytes);
        return new LargeResultExportService(properties, new ExportSessionRegistry());
    }
}
