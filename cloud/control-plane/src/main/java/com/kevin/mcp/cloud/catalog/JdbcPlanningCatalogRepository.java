package com.kevin.mcp.cloud.catalog;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 使用 MySQL 保存不可变 Catalog Snapshot 与 Runtime 当前引用。
 *
 * @author Kevin
 * @date 2026-09-11
 */
@Repository
@Profile("control-plane")
public class JdbcPlanningCatalogRepository implements PlanningCatalogRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 注入 JDBC adapter。
     *
     * @param jdbcTemplate 命名参数 JDBC 模板
     */
    public JdbcPlanningCatalogRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public PlanningCatalog createCatalog(PlanningCatalog catalog) {
        jdbcTemplate.update("""
                INSERT INTO mcp_tenant (tenant_id, status, create_time, update_time)
                VALUES (:tenantId, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE update_time = update_time
                """, new MapSqlParameterSource("tenantId", catalog.tenantId()));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update("""
                    INSERT INTO mcp_planning_catalog
                        (tenant_id, catalog_digest, create_time, update_time)
                    VALUES (:tenantId, :catalogDigest, :createdAt, :createdAt)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", catalog.tenantId())
                    .addValue("catalogDigest", catalog.catalogDigest())
                    .addValue("createdAt", Timestamp.from(catalog.createdAt())), keyHolder, new String[]{"id"});
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("Catalog digest is already registered", exception);
        }
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("Planning Catalog ID was not generated");
        }
        jdbcTemplate.update("""
                INSERT INTO mcp_planning_catalog_content (catalog_id, catalog_json, create_time, update_time)
                VALUES (:catalogId, :catalogJson, :createdAt, :createdAt)
                """, new MapSqlParameterSource().addValue("catalogId", id.longValue())
                .addValue("catalogJson", catalog.catalogJson()).addValue("createdAt", Timestamp.from(catalog.createdAt())));
        return catalog;
    }

    @Override
    public Optional<PlanningCatalog> findCatalog(String tenantId, String catalogDigest) {
        return jdbcTemplate.query("""
                SELECT c.tenant_id, c.catalog_digest, c.create_time, content.catalog_json
                FROM mcp_planning_catalog c
                INNER JOIN mcp_planning_catalog_content content ON content.catalog_id = c.id
                WHERE c.tenant_id = :tenantId AND c.catalog_digest = :catalogDigest
                """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("catalogDigest", catalogDigest), this::mapCatalog).stream().findFirst();
    }

    @Override
    @Transactional
    public RuntimeRegistration saveRegistration(RuntimeRegistration registration) {
        Integer expectedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mcp_platform_instance
                WHERE tenant_id = :tenantId AND instance_id = :instanceId AND status = 'ACTIVE'
                """, new MapSqlParameterSource().addValue("tenantId", registration.tenantId())
                .addValue("instanceId", registration.instanceId()), Integer.class);
        if (expectedCount == null || expectedCount == 0) {
            throw new IllegalStateException("Runtime instance is not declared by Platform");
        }
        jdbcTemplate.update("""
                INSERT INTO mcp_tenant (tenant_id, status, create_time, update_time)
                VALUES (:tenantId, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE update_time = update_time
                """, new MapSqlParameterSource("tenantId", registration.tenantId()));
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", registration.tenantId()).addValue("instanceId", registration.instanceId())
                .addValue("artifactVersion", registration.artifactVersion())
                .addValue("artifactDigest", registration.artifactDigest())
                .addValue("catalogDigest", registration.catalogDigest());
        jdbcTemplate.update("""
                INSERT INTO mcp_runtime_instance
                    (tenant_id, instance_id, artifact_version, artifact_digest, catalog_digest,
                     last_seen_time, create_time, update_time)
                VALUES (:tenantId, :instanceId, :artifactVersion, :artifactDigest, :catalogDigest,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE artifact_version = VALUES(artifact_version), artifact_digest = VALUES(artifact_digest),
                    catalog_digest = VALUES(catalog_digest), last_seen_time = CURRENT_TIMESTAMP,
                    update_time = CURRENT_TIMESTAMP
                """, parameters);
        return registration;
    }

    @Override
    public Optional<RuntimeRegistration> findRegistration(String tenantId, String instanceId, String artifactDigest,
                                                          String catalogDigest) {
        return jdbcTemplate.query("""
                SELECT tenant_id, instance_id, artifact_version, artifact_digest, catalog_digest
                FROM mcp_runtime_instance
                WHERE tenant_id = :tenantId AND instance_id = :instanceId
                  AND artifact_digest = :artifactDigest AND catalog_digest = :catalogDigest
                """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("instanceId", instanceId).addValue("artifactDigest", artifactDigest)
                .addValue("catalogDigest", catalogDigest),
                (resultSet, rowNumber) -> new RuntimeRegistration(resultSet.getString("tenant_id"),
                        resultSet.getString("instance_id"), resultSet.getString("artifact_version"),
                        resultSet.getString("artifact_digest"), resultSet.getString("catalog_digest")))
                .stream().findFirst();
    }

    @Override
    @Transactional
    public List<PlanningCatalog> deleteUnreferencedBefore(Instant cutoff) {
        List<PlanningCatalog> candidates = jdbcTemplate.query("""
                SELECT c.tenant_id, c.catalog_digest, c.create_time, content.catalog_json
                FROM mcp_planning_catalog c
                INNER JOIN mcp_planning_catalog_content content ON content.catalog_id = c.id
                WHERE c.create_time < :cutoff
                  AND NOT EXISTS (
                      SELECT 1 FROM mcp_runtime_instance runtime
                      WHERE runtime.tenant_id = c.tenant_id
                        AND runtime.catalog_digest = c.catalog_digest
                  )
                """, new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)), this::mapCatalog);
        List<PlanningCatalog> deleted = new ArrayList<>();
        for (PlanningCatalog candidate : candidates) {
            int affected = jdbcTemplate.update("""
                    DELETE FROM mcp_planning_catalog
                    WHERE tenant_id = :tenantId AND catalog_digest = :catalogDigest
                      AND create_time < :cutoff
                      AND NOT EXISTS (
                          SELECT 1 FROM mcp_runtime_instance runtime
                          WHERE runtime.tenant_id = :tenantId
                            AND runtime.catalog_digest = :catalogDigest
                      )
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", candidate.tenantId())
                    .addValue("catalogDigest", candidate.catalogDigest())
                    .addValue("cutoff", Timestamp.from(cutoff)));
            if (affected == 1) {
                deleted.add(candidate);
            }
        }
        return List.copyOf(deleted);
    }

    private PlanningCatalog mapCatalog(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PlanningCatalog(resultSet.getString("tenant_id"), resultSet.getString("catalog_digest"),
                resultSet.getString("catalog_json"), resultSet.getTimestamp("create_time").toInstant());
    }
}
