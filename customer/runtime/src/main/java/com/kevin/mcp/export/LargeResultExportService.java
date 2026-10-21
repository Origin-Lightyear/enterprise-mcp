package com.kevin.mcp.export;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 大结果查询的统一导出服务；分流、流式写出、列画像与下载会话全在这里收口。
 *
 * <p>分页型数据源通过 {@code beginExport} 加 {@code appendPage} 流式接入，
 * 一次成型数据源通过 {@code exportRows} 接入；服务保证内存中最多只驻留
 * 阈值加一页的数据，绝不为了估算大小把全量行加载进内存。</p>
 *
 * @author Kevin
 * @date 2026-10-19
 */
@Service
public class LargeResultExportService {

    private static final Logger log = LoggerFactory.getLogger(LargeResultExportService.class);

    /** 短码字符集；去掉 0/O/1/l 防止人工转抄混淆。 */
    private static final char[] CODE_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    /** 短码长度；62 进制 10 位约 8.4e17 组合，暴力猜解不可行。 */
    private static final int CODE_LENGTH = 10;

    /** 导出文件名时间戳格式。 */
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ExportProperties properties;
    private final ExportSessionRegistry registry;
    private final SecureRandom random = new SecureRandom();

    /**
     * 注入导出配置与会话注册表。
     *
     * @param properties 导出部署配置
     * @param registry 下载会话注册表
     */
    public LargeResultExportService(ExportProperties properties, ExportSessionRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * 判断给定行数与序列化字节数是否仍可内联返回。
     *
     * @param rowCount 当前行数
     * @param serializedBytes 估算序列化字节数
     * @return true 表示小结果，可以内联
     */
    public boolean isInline(int rowCount, int serializedBytes) {
        return rowCount <= properties.getInlineMaxRows()
                && serializedBytes <= properties.getInlineMaxBytes();
    }

    /**
     * 开始一次流式导出；内部先写入临时文件，全部行写完后才移动到正式路径。
     *
     * @param queryId 业务查询标识，用于生成文件名
     * @param fields 字段名列表
     * @return 流式导出会话
     * @throws IllegalStateException 导出目录不可创建或不可写
     */
    public StreamingExport beginExport(String queryId, List<String> fields) {
        Path directory = resolveExportDirectory();
        String sanitized = queryId.codePoints()
                .mapToObj(cp -> (cp >= 0x4E00 && cp <= 0x9FA5) || Character.isLetterOrDigit(cp)
                        ? Character.toString(cp) : "_")
                .collect(java.util.stream.Collectors.joining());
        String fileName = sanitized + "_" + LocalDateTime.now().format(FILE_TIMESTAMP)
                + ("xlsx".equalsIgnoreCase(properties.getFormat()) ? ".xlsx" : ".csv");
        Path target = directory.resolve(fileName);
        Path temp = directory.resolve(fileName + ".part");
        boolean xlsx = "xlsx".equalsIgnoreCase(properties.getFormat());
        if (xlsx) {
            target = Path.of(target.toString().replaceAll("\\.csv$", "") + ".xlsx");
            temp = Path.of(temp.toString().replaceAll("\\.csv$", "") + ".xlsx.part");
        }
        try {
            if (xlsx) {
                SXSSFWorkbook workbook = new SXSSFWorkbook(200);
                Sheet sheet = workbook.createSheet(sanitizeSheetName(queryId));
                CellStyle headerStyle = createHeaderStyle(workbook);
                CellStyle headerGroupStyle = createHeaderGroupStyle(workbook);
                CellStyle amountStyle = createAmountStyle(workbook);
                CellStyle dateStyle = createDateStyle(workbook);
                Row headerRow = sheet.createRow(0);
                for (int i = 0; i < fields.size(); i++) {
                    Cell cell = headerRow.createCell(i);
                    boolean erpStyle = isErpHeader(fields.get(i));
                    cell.setCellValue(fields.get(i));
                    cell.setCellStyle(erpStyle ? headerGroupStyle : headerStyle);
                    sheet.setColumnWidth(i, 20 * 256);
                }
                sheet.createFreezePane(0, 1);
                sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, Math.max(0, fields.size() - 1)));
                return new StreamingExport(queryId, fields, temp, target, null,
                        new ColumnProfiler(fields), workbook, sheet,
                        amountStyle, dateStyle);
            }
            BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
            // UTF-8 BOM 保证 Excel 直接打开中文列头不乱码。
            writer.write('\ufeff');
            writer.write(String.join(",", fields.stream().map(this::escape).toList()));
            writer.newLine();
            return new StreamingExport(queryId, fields, temp, target, writer,
                    new ColumnProfiler(fields));
        } catch (IOException exception) {
            throw new IllegalStateException("K3 export directory is not writable: " + directory, exception);
        }
    }

    /**
     * 把一行数据追加到流式导出；值统一转字符串写出。
     *
     * @param export 流式导出会话
     * @param row 行数据，顺序与 fields 一致
     * @throws IllegalStateException 超过单文件最大行数
     */
    public void appendRow(StreamingExport export, List<?> row) {
        if (export.rowCount + 1 > properties.getMaxExportRows()) {
            throw new IllegalStateException("Export exceeds maximum rows: " + properties.getMaxExportRows());
        }
        if (export.workbook != null) {
            writeXlsxRow(export, row);
            export.rowCount++;
            export.profiler.observe(row);
            return;
        }
        try {
            List<String> cells = new ArrayList<>(row.size());
            for (Object value : row) {
                cells.add(escape(value == null ? "" : String.valueOf(value)));
            }
            export.writer.write(String.join(",", cells));
            export.writer.newLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write export row", exception);
        }
        export.rowCount++;
        export.profiler.observe(row);
    }

    /**
     * 完成流式导出；生成短码、注册一次性下载会话并返回统一文件模式结果。
     *
     * @param export 流式导出会话
     * @return 文件模式统一返回体
     * @throws IllegalStateException 临时文件无法提交
     */
    public ExportedQueryResult finishExport(StreamingExport export) {
        try {
            if (export.workbook != null) {
                export.workbook.write(Files.newOutputStream(export.tempFile));
                export.workbook.dispose();
                export.workbook.close();
            } else {
                export.writer.flush();
                export.writer.close();
            }
            Files.move(export.tempFile, export.targetFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            deleteQuietly(export.tempFile);
            throw new IllegalStateException("Failed to commit export file", exception);
        }
        Instant now = Instant.now();
        Instant downloadExpiresAt = now.plus(properties.getDownloadTokenTtl());
        Instant fileExpiresAt = now.plus(properties.getFileRetention());
        String fileId = generateFileId();
        registry.register(new ExportSessionRegistry.ExportSession(fileId,
                export.targetFile.toString(), export.targetFile.getFileName().toString(),
                export.queryId(), downloadExpiresAt, fileExpiresAt, false));
        String baseUrl = properties.getPublicBaseUrl() == null || properties.getPublicBaseUrl().isBlank()
                ? "http://localhost:6001" : properties.getPublicBaseUrl();
        String downloadUrl = baseUrl + "/internal/k3-export/" + fileId;
        return ExportedQueryResult.file(export.queryId(), export.rowCount, export.fields,
                export.profiler.summary(), fileId, export.targetFile.getFileName().toString(),
                downloadUrl, downloadExpiresAt.toString(), fileExpiresAt.toString(),
                "文件已生成，请立即下载到本地；服务器文件将于 " + fileExpiresAt + " 过期删除");
    }

    /**
     * 导出已在内存中的小体量数据源（如 Wren 计划结果）。
     *
     * @param queryId 业务查询标识
     * @param fields 字段名列表
     * @param rows 行数据
     * @return 文件模式统一返回体
     */
    public ExportedQueryResult exportRows(String queryId, List<String> fields, List<? extends List<?>> rows) {
        StreamingExport export = beginExport(queryId, fields);
        try {
            for (List<?> row : rows) {
                appendRow(export, row);
            }
        } catch (RuntimeException exception) {
            cancelExport(export);
            throw exception;
        }
        return finishExport(export);
    }

    /**
     * 放弃导出并删除临时文件；用于分页中途金蝶报错的场景。
     *
     * @param export 流式导出会话
     */
    public void cancelExport(StreamingExport export) {
        try {
            export.writer.close();
        } catch (IOException ignored) {
            // 临时文件即将删除，关闭失败无需阻断主流程。
        }
        deleteQuietly(export.tempFile);
    }

    /**
     * 消费一次性短码并返回文件路径；不存在、过期或已下载时为空。
     *
     * @param fileId 下载短码
     * @return 导出会话
     */
    public ExportSessionRegistry.ExportSession consume(String fileId) {
        return registry.consume(fileId).orElseThrow(() -> new ExportNotFoundException());
    }

    /**
     * 定时清理过期文件与孤儿文件；进程启动残留靠文件修改时间兜底删除。
     */
    @Scheduled(fixedDelayString = "PT1H")
    public void cleanup() {
        registry.removeExpired();
        Path directory = resolveExportDirectory();
        Instant retentionDeadline = Instant.now().minus(properties.getFileRetention());
        long maxTotalBytes = properties.getMaxTotalMb() * 1024L * 1024L;
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> ordered = files
                    .filter(path -> path.toString().endsWith(".csv"))
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .toList();
            long totalBytes = 0;
            List<Path> overflow = new ArrayList<>();
            for (Path file : ordered) {
                long size = Files.size(file);
                if (lastModified(file).isBefore(retentionDeadline)) {
                    deleteQuietly(file);
                    continue;
                }
                if (totalBytes + size > maxTotalBytes) {
                    overflow.add(file);
                } else {
                    totalBytes += size;
                }
            }
            overflow.forEach(this::deleteQuietly);
        } catch (IOException exception) {
            log.warn("Export cleanup failed: {}", exception.getMessage());
        }
    }

    /**
     * 生成全局唯一短码；冲突时重试，注册表规模小不会形成活锁。
     *
     * @return 10 位 Base62 短码
     */
    private String generateFileId() {
        while (true) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int index = 0; index < CODE_LENGTH; index++) {
                code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            if (registry.find(code.toString()).isEmpty()) {
                return code.toString();
            }
        }
    }

    /**
     * 解析并确保导出目录存在。
     *
     * @return 导出目录
     */
    private Path resolveExportDirectory() {
        String configured = properties.getExportDir();
        Path directory = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "enterprise-mcp-exports")
                : Path.of(configured);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Export directory is not writable: " + directory, exception);
        }
        return directory;
    }

    /**
     * 读取文件修改时间；读取失败按最早时间处理，允许清理任务删除。
     *
     * @param file 目标文件
     * @return 修改时间
     */
    private Instant lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException exception) {
            return Instant.EPOCH;
        }
    }

    /**
     * 按标准 CSV 规则转义单元格；含逗号、引号或换行时加引号并翻倍内部引号。
     *
     * @param value 原始单元格文本
     * @return 转义后的文本
     */
    private String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /**
     * 静默删除文件；清理路径上的失败只记日志。
     *
     * @param file 目标文件
     */
    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Failed to delete export file {}: {}", file, exception.getMessage());
        }
    }

    /**
     * 流式导出会话；只暴露必要字段给工具层逐页喂入。
     *
     * @author Kevin
     * @date 2026-10-19
     */
    public static final class StreamingExport {

        /** 业务查询标识。 */
        private final String queryId;

        /** 字段名列表。 */
        private final List<String> fields;

        /** 临时文件路径。 */
        private final Path tempFile;

        /** 正式目标文件路径。 */
        private final Path targetFile;

        /** CSV 字符写出器。 */
        private final BufferedWriter writer;

        /** 列画像统计器。 */
        private final ColumnProfiler profiler;

        /** 已写出行数。 */
        private long rowCount;

        /** XLSX 流式工作簿；CSV 模式为 null。 */
        private final SXSSFWorkbook workbook;

        /** XLSX 当前工作表。 */
        private final Sheet sheet;

        /** XLSX 金额样式。 */
        private final CellStyle amountStyle;

        /** XLSX 日期样式。 */
        private final CellStyle dateStyle;

        private StreamingExport(String queryId, List<String> fields, Path tempFile, Path targetFile,
                                BufferedWriter writer, ColumnProfiler profiler) {
            this(queryId, fields, tempFile, targetFile, writer, profiler, null, null, null, null);
        }

        private StreamingExport(String queryId, List<String> fields, Path tempFile, Path targetFile,
                                BufferedWriter writer, ColumnProfiler profiler,
                                SXSSFWorkbook workbook, Sheet sheet,
                                CellStyle amountStyle, CellStyle dateStyle) {
            this.queryId = queryId;
            this.fields = List.copyOf(fields);
            this.tempFile = tempFile;
            this.targetFile = targetFile;
            this.writer = writer;
            this.profiler = profiler;
            this.workbook = workbook;
            this.sheet = sheet;
            this.amountStyle = amountStyle;
            this.dateStyle = dateStyle;
        }

        /**
         * 已写出行数。
         *
         * @return 行数
         */
        public long rowCount() {
            return rowCount;
        }

        /**
         * 业务查询标识。
         *
         * @return 查询标识
         */
        public String queryId() {
            return queryId;
        }

        /**
         * 字段名列表。
         *
         * @return 字段名列表
         */
        public java.util.List<String> fields() {
            return fields;
        }

        /**
         * 列画像摘要。
         *
         * @return 列画像统计
         */
        public java.util.Map<String, Object> summary() {
            return profiler.summary();
        }
    }

    /**
     * 短码不存在或已失效的受控异常；Controller 统一映射 404。
     *
     * @author Kevin
     * @date 2026-10-19
     */
    public static class ExportNotFoundException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    /**
     * 流式列画像统计器；低基数上限防止高基数列把摘要撑大。
     *
     * @author Kevin
     * @date 2026-10-19
     */
    /**
     * 判断列头是否属于 ERP 凭证级单据头，用于按 ERP 风格绘制头字段背景。
     *
     * @param name 中文列头
     * @return 是单据头列返回 true
     */
    private static boolean isErpHeader(String name) {
        return name != null && name.contains("凭证") || name != null && (
                name.contains("账簿") || name.contains("核算组织") || name.contains("借方总")
                        || name.contains("贷方总") || name.contains("创建日期") || name.contains("修改日期")
                        || name.contains("审核日期") || name.contains("备注"));
    }

    /**
     * 构造通用表头样式。
     */
    private static CellStyle createHeaderStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_80_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
        return style;
    }

    /**
     * 构造 ERP 单据头分组样式。
     */
    private static CellStyle createHeaderGroupStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
        return style;
    }

    /**
     * 构造金额单元格两位小数样式。
     */
    private static CellStyle createAmountStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("#,##0.00;[Red]-#,##0.00"));
        return style;
    }

    /**
     * 构造日期单元格 yyyy/m/d 显示样式。
     */
    private static CellStyle createDateStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        DataFormat format = workbook.createDataFormat();
        style.setDataFormat(format.getFormat("yyyy/m/d"));
        return style;
    }

    /**
     * 写入一行 XLSX 明细，金额、日期和布尔状态分别格式化，普通值转字符串。
     */
    private void writeXlsxRow(StreamingExport export, List<?> row) {
        int rowIndex = export.sheet.getLastRowNum() + 1;
        if (export.rowCount == 0 && export.sheet.getRow(0) != null) {
            rowIndex = 1;
        }
        Row dataRow = export.sheet.createRow(rowIndex);
        for (int i = 0; i < row.size(); i++) {
            Cell cell = dataRow.createCell(i);
            Object value = row.get(i);
            String fieldName = i < export.fields.size() ? export.fields.get(i) : "";
            if (value == null || String.valueOf(value).isBlank()) {
                cell.setBlank();
                continue;
            }
            if (fieldName.contains("金额") || fieldName.contains("借方") || fieldName.contains("贷方")
                    || fieldName.contains("单价") || fieldName.contains("数量") || fieldName.contains("汇率")) {
                try {
                    cell.setCellValue(new BigDecimal(String.valueOf(value)).doubleValue());
                    cell.setCellStyle(export.amountStyle);
                    continue;
                } catch (NumberFormatException ignored) {
                    // 非数字兜底按文本写出，避免脏数据导致导出失败。
                }
            }
            if (fieldName.contains("日期")) {
                try {
                    cell.setCellValue(java.time.LocalDate.parse(String.valueOf(value).substring(0, 10)));
                    cell.setCellStyle(export.dateStyle);
                    continue;
                } catch (RuntimeException ignored) {
                    // 非标准日期兜底按文本写出。
                }
            }
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                cell.setCellValue(String.valueOf(value));
            }
        }
    }

    /**
     * 清理表单标识为合法 XLSX Sheet 名称。
     */
    private static String sanitizeSheetName(String queryId) {
        String safe = queryId == null || queryId.isBlank() ? "导出" : queryId;
        return safe.replaceAll("[\\\\/*?:\\[\\]]", "_");
    }

    static final class ColumnProfiler {

        /** 低基数列允许的最大 distinct 取值数；超过即放弃该列分布。 */
        private static final int MAX_DISTINCT = 20;

        private final List<String> fields;
        private final List<Long> numericCount;
        private final List<Double> numericSum;
        private final List<Double> numericMin;
        private final List<Double> numericMax;
        private final List<Map<String, Long>> distinctCounts;
        private final List<Boolean> distinctOverflow;

        ColumnProfiler(List<String> fields) {
            this.fields = List.copyOf(fields);
            int size = fields.size();
            this.numericCount = new ArrayList<>(size);
            this.numericSum = new ArrayList<>(size);
            this.numericMin = new ArrayList<>(size);
            this.numericMax = new ArrayList<>(size);
            this.distinctCounts = new ArrayList<>(size);
            this.distinctOverflow = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                this.numericCount.add(0L);
                this.numericSum.add(0.0);
                this.numericMin.add(null);
                this.numericMax.add(null);
                this.distinctCounts.add(new LinkedHashMap<>());
                this.distinctOverflow.add(false);
            }
        }

        /**
         * 观察一行数据并更新各列统计。
         *
         * @param row 行数据
         */
        void observe(List<?> row) {
            for (int index = 0; index < fields.size() && index < row.size(); index++) {
                Object value = row.get(index);
                if (value instanceof Number number) {
                    observeNumeric(index, number.doubleValue());
                } else if (value != null && !String.valueOf(value).isBlank()) {
                    // 单据头字段按凭证首条分录分组后，后续空串是布局占位，不应进入取值分布。
                    observeDistinct(index, String.valueOf(value));
                }
            }
        }

        /**
         * 输出列画像摘要；结构对 Agent 图表生成友好。
         *
         * @return 列名到统计信息的映射
         */
        Map<String, Object> summary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            for (int index = 0; index < fields.size(); index++) {
                Map<String, Object> column = new LinkedHashMap<>();
                if (numericCount.get(index) > 0) {
                    long count = numericCount.get(index);
                    column.put("numericCount", count);
                    column.put("sum", round(numericSum.get(index)));
                    column.put("min", round(numericMin.get(index)));
                    column.put("max", round(numericMax.get(index)));
                    column.put("avg", round(numericSum.get(index) / count));
                }
                if (!distinctOverflow.get(index) && !distinctCounts.get(index).isEmpty()) {
                    column.put("distribution", Map.copyOf(distinctCounts.get(index)));
                } else if (distinctOverflow.get(index)) {
                    column.put("distribution", "HIGH_CARDINALITY");
                }
                if (!column.isEmpty()) {
                    summary.put(fields.get(index), column);
                }
            }
            return summary;
        }

        /**
         * 累加数值列统计。
         *
         * @param index 列下标
         * @param value 数值
         */
        private void observeNumeric(int index, double value) {
            numericCount.set(index, numericCount.get(index) + 1);
            numericSum.set(index, numericSum.get(index) + value);
            numericMin.set(index, numericMin.get(index) == null ? value
                    : Math.min(numericMin.get(index), value));
            numericMax.set(index, numericMax.get(index) == null ? value
                    : Math.max(numericMax.get(index), value));
        }

        /**
         * 累加文本列 distinct 计数；超过上限后停止追踪该列。
         *
         * @param index 列下标
         * @param value 文本值
         */
        private void observeDistinct(int index, String value) {
            if (distinctOverflow.get(index)) {
                return;
            }
            Map<String, Long> counts = distinctCounts.get(index);
            counts.merge(value, 1L, Long::sum);
            if (counts.size() > MAX_DISTINCT) {
                distinctCounts.set(index, new LinkedHashMap<>());
                distinctOverflow.set(index, true);
            }
        }

        /**
         * 保留两位小数，避免浮点误差进入摘要。
         *
         * @param value 原始数值
         * @return 舍入后的数值
         */
        private static Double round(Double value) {
            return value == null ? null : Math.round(value * 100.0) / 100.0;
        }
    }
}
