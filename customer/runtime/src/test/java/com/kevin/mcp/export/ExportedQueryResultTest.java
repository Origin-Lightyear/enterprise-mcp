package com.kevin.mcp.export;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证表格统一返回体的对象行保形、计数和 file 降级位置化。
 *
 * @author Kevin
 * @date 2026-10-19
 */
class ExportedQueryResultTest {

    @TempDir
    private Path directory;

    private LargeResultExportService service;

    @BeforeEach
    void setUp() {
        ExportProperties properties = new ExportProperties();
        properties.setExportDir(directory.toString());
        properties.setPublicBaseUrl("https://mcp.example.com");
        service = new LargeResultExportService(properties, new ExportSessionRegistry());
    }

    /** 小结果 inline：对象行保持 Map 形态，totalCount 不丢行。 */
    @Test
    void shouldKeepObjectRowsInlineWithRealCount() {
        List<Map<String, Object>> rows = List.of(
                Map.of("id", 1, "name", "北京朝阳旗舰店"),
                Map.of("id", 2, "name", "上海陆家嘴店"));
        ExportedQueryResult result = ExportedQueryResult.fromTabular(
                "retail_erp_demo", List.of("id", "name"), rows, false, service);
        assertEquals("inline", result.mode());
        assertEquals(2, result.totalCount());
        assertEquals(rows, result.rows());
                assertTrue(((Map<?, ?>) result.summary().get("id")).containsKey("avg"));
    }

    /** 大结果 file：对象行按 fields 位置化导出，totalCount 仍是原始行数。 */
    @Test
    void shouldPositionalizeObjectRowsForFileWithRealCount() throws Exception {
        ExportProperties properties = new ExportProperties();
        properties.setExportDir(directory.toString());
        properties.setPublicBaseUrl("https://mcp.example.com");
        properties.setInlineMaxRows(1);
        properties.setInlineMaxBytes(16);
        LargeResultExportService tightService = new LargeResultExportService(
                properties, new ExportSessionRegistry());
        List<Map<String, Object>> rows = List.of(
                Map.of("id", 1, "name", "北京朝阳旗舰店"),
                Map.of("id", 2, "name", "上海陆家嘴店"));
        ExportedQueryResult result = ExportedQueryResult.fromTabular(
                "retail_erp_demo", List.of("id", "name"), rows, false, tightService);
        assertEquals("file", result.mode());
        assertEquals(2, result.totalCount());
        assertEquals(List.of("id", "name"), result.fields());
    }

    /** 数组行（K3 契约）同样可走统一入口。 */
    @Test
    void shouldSupportArrayRows() {
        List<List<?>> rows = List.of(
                List.of(1, "北京朝阳旗舰店"),
                List.of(2, "上海陆家嘴店"));
        ExportedQueryResult result = ExportedQueryResult.fromTabular(
                "GL_Voucher_New", List.of("id", "name"), rows, false, service);
        assertEquals("inline", result.mode());
        assertEquals(2, result.totalCount());
        assertEquals(rows, result.rows());
    }

    /** 非法行形态必须显式失败，禁止静默丢行。 */
    @Test
    void shouldRejectUnknownRowType() {
        List<?> rows = List.of("not-a-row");
        assertThrows(IllegalArgumentException.class, () -> ExportedQueryResult.fromTabular(
                "retail_erp_demo", List.of("id"), rows, false, service));
    }
}

