package com.kevin.mcp.resource.jdbc;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 通过显式资源编号执行客户内网只读 JDBC 查询。
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Component
public class JdbcQueryConnector {

    private final ResourceDataSourceRegistry dataSourceRegistry;

    /**
     * 注入多资源数据源注册表。
     *
     * @param dataSourceRegistry 数据源注册表
     */
    public JdbcQueryConnector(ResourceDataSourceRegistry dataSourceRegistry) {
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * 在指定资源上执行命名参数只读查询。
     *
     * <p>连接器拒绝非 SELECT/WITH 语句和多语句 SQL，防止云端计划或错误配置把写操作带入只读能力。</p>
     *
     * @param resourceProfileId 数据源逻辑编号
     * @param sql 已审核的客户 SQL 模板
     * @param parameters 命名参数
     * @return 查询结果行列表
     */
    public List<Map<String, Object>> query(String resourceProfileId, String sql, Map<String, ?> parameters) {
        validateReadOnlySql(sql);
        DataSource dataSource = dataSourceRegistry.get(resourceProfileId)
                .orElseThrow(() -> new IllegalArgumentException("JDBC resource is not configured: " + resourceProfileId));
        NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(dataSource);
        List<Map<String, Object>> rows = template.queryForList(sql, parameters == null ? Map.of() : parameters);
        int maxRows = dataSourceRegistry.maxRows(resourceProfileId);
        if (rows.size() > maxRows) {
            return List.copyOf(rows.subList(0, maxRows));
        }
        return rows;
    }

    /**
     * 读取 JAR 内客户审核 SQL 模板后执行，模板路径限定在 sql/ 目录。
     *
     * @param resourceProfileId 数据源逻辑编号
     * @param templateName 模板相对名称，例如 finance/find-journal.sql
     * @param parameters 命名参数
     * @return 查询结果
     */
    public List<Map<String, Object>> queryTemplate(String resourceProfileId, String templateName, Map<String, ?> parameters) {
        if (templateName == null || templateName.isBlank() || templateName.contains("..") || templateName.startsWith("/")) {
            throw new IllegalArgumentException("SQL template name is invalid");
        }
        ClassPathResource resource = new ClassPathResource("sql/" + templateName);
        try (var input = resource.getInputStream()) {
            return query(resourceProfileId, new String(input.readAllBytes(), StandardCharsets.UTF_8), parameters);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load SQL template: " + templateName, exception);
        }
    }

    /**
     * 校验 SQL 仅包含一条只读语句。
     *
     * @param sql SQL 模板
     */
    private void validateReadOnlySql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be blank");
        }
        String normalized = sql.strip().toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("select") || normalized.startsWith("with")) || normalized.contains(";")) {
            throw new IllegalArgumentException("Only one read-only SELECT/WITH statement is allowed");
        }
    }
}
