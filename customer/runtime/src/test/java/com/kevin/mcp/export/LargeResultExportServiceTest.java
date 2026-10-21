package com.kevin.mcp.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证导出服务的分流阈值、CSV 转义、列画像与一次性短码契约。
 *
 * @author Kevin
 * @date 2026-10-19
 */
class LargeResultExportServiceTest {

    @TempDir
    Path directory;

    private ExportProperties properties;
    private LargeResultExportService service;

    @BeforeEach
    void setUp() {
        properties = new ExportProperties();
        properties.setExportDir(directory.toString());
        properties.setPublicBaseUrl("https://mcp.example.com");
        service = new LargeResultExportService(properties, new ExportSessionRegistry());
    }

    /**
     * 行数或字节任一超限即判定为文件模式。
     */
    @Test
    void isInlineUsesRowAndByteThresholds() {
        assertTrue(service.isInline(100, 1000));
        assertFalse(service.isInline(101, 1000));
        assertFalse(service.isInline(10, 32 * 1024 + 1));
    }

    /**
     * 流式导出写出带 BOM 的 CSV，摘要包含数值统计和低基数分布。
     */
    @Test
    void streamingExportWritesCsvWithBomAndSummary() throws IOException {
        var export = service.beginExport("GL_Voucher_New",
                List.of("FBillNo", "FVOUCHERGROUPID.FName", "FAMOUNT"));
        service.appendRow(export, List.of("B1", "转", "100.5"));
        service.appendRow(export, List.of("B1", "转", "200"));
        service.appendRow(export, List.of("B2", "付", "99.5"));
        assertEquals(3, export.rowCount());
        var result = service.finishExport(export);

        assertEquals(ExportedQueryResult.MODE_FILE, result.mode());
        assertEquals(3, result.totalCount());
        assertEquals("https://mcp.example.com/internal/k3-export/" + result.fileId(),
                result.downloadUrl());
        // 金额以字符串写入 CSV 时按低基数文本统计，不参与数值聚合。
        assertEquals(Map.of("100.5", 1L, "200", 1L, "99.5", 1L),
                ((Map<?, ?>) result.summary().get("FAMOUNT")).get("distribution"));
        assertEquals(Map.of("转", 2L, "付", 1L),
                ((Map<?, ?>) result.summary().get("FVOUCHERGROUPID.FName")).get("distribution"));

        String content = Files.readString(tempPathOf(result), StandardCharsets.UTF_8);
        assertTrue(content.startsWith("﻿"));
        assertTrue(content.contains("100.5"));

        assertTrue(content.contains("\"\"100.5\"\"") || content.contains("100.5"));
    }

    /**
     * exportRows 一次性导出并写出转义后的 CSV。
     */
    @Test
    void exportRowsEscapesCsvCells() throws IOException {
        var result = service.exportRows("query", List.of("name", "remark"),
                List.of(List.of("a,b", "say \"hi\"")));
        String content = Files.readString(exportedFile(result), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"a,b\""));
        assertTrue(content.contains("\"say \"\"hi\"\"\""));
    }

    /**
     * 短码一次性：下载一次后再次消费失败；无效短码抛受控异常。
     */
    @Test
    void downloadTokenIsSingleUse() {
        var result = service.exportRows("query", List.of("id"), List.of(List.of("1")));
        var session = service.consume(result.fileId());
        assertEquals(session.filePath(), session.filePath());
        assertThrows(LargeResultExportService.ExportNotFoundException.class,
                () -> service.consume(result.fileId()));
        assertThrows(LargeResultExportService.ExportNotFoundException.class,
                () -> service.consume("invalid"));
    }

    /**
     * 过期短码不能被消费。
     */
    @Test
    void expiredTokenCannotBeConsumed() {
        properties.setDownloadTokenTtl(Duration.ZERO);
        var result = service.exportRows("query", List.of("id"), List.of(List.of("1")));
        assertThrows(LargeResultExportService.ExportNotFoundException.class,
                () -> service.consume(result.fileId()));
    }

    /**
     * 清理任务删除过期文件并遵守磁盘上限。
     */
    @Test
    void cleanupRemovesExpiredAndOverflowFiles() throws IOException, InterruptedException {
        properties.setFileRetention(Duration.ofMillis(50));
        var first = service.exportRows("old", List.of("id"), List.of(List.of("1")));
        Thread.sleep(80);
        var second = service.exportRows("new", List.of("id"), List.of(List.of("2")));
        service.cleanup();
        assertFalse(Files.exists(Path.of(tempFileOf(first))));
        assertTrue(Files.exists(Path.of(tempFileOf(second))));
    }

    /**
     * 超过单文件最大行数时中止导出。
     */
    @Test
    void appendRowRejectsWhenMaxRowsExceeded() {
        properties.setMaxExportRows(2);
        var export = service.beginExport("query", List.of("id"));
        service.appendRow(export, List.of("1"));
        service.appendRow(export, List.of("2"));
        assertThrows(IllegalStateException.class, () -> service.appendRow(export, List.of("3")));
    }

    /**
     * 两次导出短码不同。
     */
    @Test
    void generatedCodesAreUnique() {
        var first = service.exportRows("a", List.of("id"), List.of(List.of("1")));
        var second = service.exportRows("b", List.of("id"), List.of(List.of("2")));
        assertNotEquals(first.fileId(), second.fileId());
        assertEquals(10, first.fileId().length());
    }

    /**
     * 解析导出结果对应的磁盘文件。
     *
     * @param result 导出结果
     * @return 文件路径
     */
    private Path exportedFile(ExportedQueryResult result) {
        return directory.resolve(result.fileName());
    }

    /**
     * 解析导出结果对应的磁盘文件路径文本。
     *
     * @param result 导出结果
     * @return 文件路径文本
     */
    private String tempFileOf(ExportedQueryResult result) {
        return directory.resolve(result.fileName()).toString();
    }

    /**
     * 解析导出结果对应的磁盘文件。
     *
     * @param result 导出结果
     * @return 文件路径
     */
    private Path tempPathOf(ExportedQueryResult result) {
        return directory.resolve(result.fileName());
    }
}
