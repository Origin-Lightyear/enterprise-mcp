package com.kevin.mcp.resource.jdbc;

import com.kevin.mcp.resource.ResourceType;
import com.kevin.mcp.resource.configuration.ResourceProfileProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.stereotype.Component;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 管理客户 Runtime 中按资源编号隔离的数据源连接池。
 *
 * <p>该注册表不使用全局 ThreadLocal 路由，调用方必须显式提供资源编号，
 * 这样异步步骤和跨数据库计划不会因为线程复用而误选数据源。</p>
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Component
public class ResourceDataSourceRegistry {

    private final Map<String, DataSource> dataSources;
    private final ResourceProfileProperties properties;

    /**
     * 根据配置创建独立数据源。
     *
     * @param properties 多资源配置
     */
    public ResourceDataSourceRegistry(ResourceProfileProperties properties) {
        this.properties = properties;
        Map<String, DataSource> created = new LinkedHashMap<>();
        properties.getProfiles().forEach((resourceId, profile) -> {
            if (profile.getType() == ResourceType.JDBC && profile.getUrl() != null && !profile.getUrl().isBlank()) {
                DataSourceBuilder<?> builder = DataSourceBuilder.create().type(HikariDataSource.class).url(profile.getUrl());
                if (profile.getUsername() != null) {
                    builder.username(profile.getUsername());
                }
                if (profile.getPassword() != null) {
                    builder.password(profile.getPassword());
                }
                HikariDataSource dataSource = (HikariDataSource) builder.build();
                dataSource.setMaximumPoolSize(profile.getMaxPoolSize());
                dataSource.setConnectionTimeout(profile.getConnectTimeout().toMillis());
                dataSource.setValidationTimeout(profile.getConnectTimeout().toMillis());
                created.put(resourceId, dataSource);
            }
        });
        this.dataSources = Map.copyOf(created);
    }

    /**
     * 按资源编号取得数据源。
     *
     * @param resourceProfileId 资源编号
     * @return 对应数据源；不存在时返回空
     */
    public Optional<DataSource> get(String resourceProfileId) {
        return Optional.ofNullable(this.dataSources.get(resourceProfileId));
    }

    /**
     * 返回已创建数据源的只读快照。
     *
     * @return 数据源映射
     */
    public Map<String, DataSource> snapshot() {
        return this.dataSources;
    }

    /** 返回资源配置的单次查询行数上限。 */
    public int maxRows(String resourceProfileId) {
        return properties.getProfiles().containsKey(resourceProfileId)
                ? properties.getProfiles().get(resourceProfileId).getMaxRows() : 1000;
    }
}
