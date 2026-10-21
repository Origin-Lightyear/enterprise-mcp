package com.kevin.mcp.export;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大结果查询的统一返回体；inline 与 file 模式共用同一协议，Agent 只需按 mode 分支处理。
 *
 * <p>file 模式永远不携带明细行，只携带计数、列画像摘要和下载短链，
 * 保证大数据不进入模型上下文；summary 供 Agent 直接生成图表。</p>
 *
 * @param mode 返回模式：inline（明细在 rows）或 file（明细在服务器文件）
 * @param queryId 业务查询标识，例如 FormId 或计划编号
 * @param totalCount 真实总行数；file 模式由服务端流式统计保证准确
 * @param fields 字段名列表，顺序与文件列一致
 * @param rows inline 模式的明细行；file 模式为空列表
 * @param hasMore inline 模式是否还有后续分页；file 模式固定 false，表示服务端已拉全量
 * @param summary 列画像统计；数值列含 sum/min/max/avg，低基数文本列含取值分布
 * @param fileId 导出文件短码；inline 模式为 null
 * @param fileName 导出文件名；inline 模式为 null
 * @param downloadUrl 完整下载短链；inline 模式为 null
 * @param downloadExpiresAt 短码过期时间；inline 模式为 null
 * @param fileExpiresAt 服务器文件过期时间；inline 模式为 null
 * @param instruction 给 Agent 的行动指令；inline 模式为 null
 *
 * @author Kevin
 * @date 2026-10-19
 */
public record ExportedQueryResult(
        String mode,
        String queryId,
        long totalCount,
        List<String> fields,
        List<?> rows,
        boolean hasMore,
        Map<String, Object> summary,
        String fileId,
        String fileName,
        String downloadUrl,
        String downloadExpiresAt,
        String fileExpiresAt,
        String instruction
) {

    /** inline 返回模式常量。 */
    public static final String MODE_INLINE = "inline";

    /** file 返回模式常量。 */
    public static final String MODE_FILE = "file";

    /**
     * 统一处理 Wren 等上游返回的 columns/rows 表格数据。
     *
     * <p>对象行（Map，Wren Arrow to_pylist 契约）与数组行（List，K3 分页契约）都接受；
     * inline 保留行原始形态以维持 Agent 可读的自描述契约，仅 file 降级时才位置化。
     * 行数统计永远基于原始 rows，任何无法识别的行形态都必须显式失败，禁止静默丢行。</p>
     *
     * @param queryId 业务查询标识
     * @param fields 列名列表，与 rows 中对象行的键一一对应
     * @param rawRows 上游原始行列表，允许为空但不允许为 null
     * @param truncated 上游是否因安全上限截断
     * @param exportService 大结果导出服务，用于 file 模式降级
     * @return inline 或 file 模式的统一返回体
     */
    public static ExportedQueryResult fromTabular(String queryId, List<String> fields,
                                                  List<?> rawRows, boolean truncated,
                                                  LargeResultExportService exportService) {
        if (rawRows == null || fields == null) {
            throw new IllegalArgumentException("fields and rows are required");
        }
        // 先做形态校验，防止部分行非法时只导出了部分数据。
        for (Object raw : rawRows) {
            if (!(raw instanceof Map<?, ?>) && !(raw instanceof List<?>)) {
                throw new IllegalArgumentException(
                        "Unsupported row type: " + raw.getClass().getName()
                                + "; expected Map (object row) or List (array row)");
            }
        }
        List<List<?>> positionalRows = positionalRows(fields, rawRows);
        if (exportService.isInline(positionalRows.size(), estimateBytes(fields, positionalRows))) {
            Map<String, Object> summary = buildSummary(fields, rawRows);
            return inline(queryId, fields, rawRows, truncated, summary);
        }
        ExportedQueryResult exported = exportService.exportRows(queryId, fields, positionalRows);
        return withTotals(exported, rawRows.size());
    }

    /**
     * 将对象行按 fields 顺序位置化；数组行原样保留。仅供 file 导出使用。
     *
     * @param fields 列名列表
     * @param rawRows 原始行列表
     * @return 位置化后的二维行
     */
    private static List<List<?>> positionalRows(List<String> fields, List<?> rawRows) {
        List<List<?>> rows = new ArrayList<>(rawRows.size());
        for (Object raw : rawRows) {
            if (raw instanceof Map<?, ?> row) {
                List<Object> values = new ArrayList<>(fields.size());
                for (String field : fields) {
                    values.add(row.get(field));
                }
                rows.add(values);
            } else {
                rows.add((List<?>) raw);
            }
        }
        return rows;
    }

    /**
     * 按字段名构建列画像；只有数值列参与统计，保持 inline 对象行契约不变。
     *
     * @param fields 列名列表
     * @param rawRows 原始行列表
     * @return 字段名到统计信息的映射
     */
    private static Map<String, Object> buildSummary(List<String> fields, List<?> rawRows) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            BigDecimal sum = BigDecimal.ZERO;
            long count = 0;
            BigDecimal min = null;
            BigDecimal max = null;
            for (Object raw : rawRows) {
                Object value = raw instanceof Map<?, ?> row
                        ? row.get(field)
                        : ((List<?>) raw).get(index);
                if (!(value instanceof Number number)) {
                    continue;
                }
                BigDecimal decimal = new BigDecimal(number.toString());
                sum = sum.add(decimal);
                count++;
                min = min == null || decimal.compareTo(min) < 0 ? decimal : min;
                max = max == null || decimal.compareTo(max) > 0 ? decimal : max;
            }
            if (count > 0) {
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("numericCount", count);
                stats.put("sum", sum.stripTrailingZeros().toPlainString());
                stats.put("min", min.toPlainString());
                stats.put("max", max.toPlainString());
                stats.put("avg", sum.divide(BigDecimal.valueOf(count),
                        java.math.MathContext.DECIMAL64).stripTrailingZeros().toPlainString());
                summary.put(field, stats);
            }
        }
        return summary;
    }

    /**
     * file 模式返回体补回真实总行数，避免位置化或导出统计丢失计数。
     *
     * @param exported 服务端导出结果
     * @param totalCount 原始总行数
     * @return 计数修正后的 file 模式返回体
     */
    private static ExportedQueryResult withTotals(ExportedQueryResult exported, long totalCount) {
        return new ExportedQueryResult(exported.mode(), exported.queryId(), totalCount,
                exported.fields(), exported.rows(), exported.hasMore(), exported.summary(),
                exported.fileId(), exported.fileName(), exported.downloadUrl(),
                exported.downloadExpiresAt(), exported.fileExpiresAt(), exported.instruction());
    }

    /**
     * 估算序列化后字节数，用于 inline/file 分流判断。
     *
     * @param fields 字段列表
     * @param positionalRows 位置化明细行
     * @return 估算字节数
     */
    private static int estimateBytes(List<String> fields, List<List<?>> positionalRows) {
        int bytes = String.join(",", fields).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for (List<?> row : positionalRows) {
            for (Object value : row) {
                bytes += value == null ? 4 : String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            }
        }
        return bytes;
    }

    /**
     * 构造小结果内联返回。
     *
     * @param queryId 业务查询标识
     * @param fields 字段名列表
     * @param rows 明细行
     * @param hasMore 是否还有后续分页
     * @param summary 列画像统计
     * @return 内联模式结果
     */
    public static ExportedQueryResult inline(String queryId, List<String> fields,
                                             List<?> rows, boolean hasMore,
                                             Map<String, Object> summary) {
        return new ExportedQueryResult(MODE_INLINE, queryId, rows.size(), fields,
                rows, hasMore, summary, null, null, null, null, null, null);
    }

    /**
     * 构造大结果文件返回。
     *
     * @param queryId 业务查询标识
     * @param totalCount 真实总行数
     * @param fields 字段名列表
     * @param summary 列画像统计
     * @param fileId 导出文件短码
     * @param fileName 导出文件名
     * @param downloadUrl 完整下载短链
     * @param downloadExpiresAt 短码过期时间
     * @param fileExpiresAt 服务器文件过期时间
     * @param instruction Agent 行动指令
     * @return 文件模式结果
     */
    public static ExportedQueryResult file(String queryId, long totalCount, List<String> fields,
                                           Map<String, Object> summary, String fileId,
                                           String fileName, String downloadUrl,
                                           String downloadExpiresAt, String fileExpiresAt,
                                           String instruction) {
        return new ExportedQueryResult(MODE_FILE, queryId, totalCount, fields,
                List.of(), false, summary, fileId, fileName, downloadUrl,
                downloadExpiresAt, fileExpiresAt, instruction);
    }
}
