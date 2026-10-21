package com.kevin.mcp.cloud.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 验证知识快照读取受当前 Catalog 摘要约束。
 *
 * @author Kevin
 * @date 2026-10-14
 */
class PlanningKnowledgeServiceTest {

    /**
     * 验证同一内容摘要不能越过 Catalog 版本边界被误用。
     */
    @Test
    void requireUsesCatalogDigestInSnapshotLookup() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PlanningCatalogService catalogs = mock(PlanningCatalogService.class);
        when(catalogs.findRegistration("tenant", "instance", "artifact", "catalog"))
                .thenReturn(java.util.Optional.of(mock(RuntimeRegistration.class)));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        PlanningKnowledgeService service = new PlanningKnowledgeService(jdbc, catalogs);

        assertThrows(IllegalStateException.class,
                () -> service.require("tenant", "instance", "artifact", "catalog", "knowledge"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertTrue(sql.getValue().contains("catalog_digest = :catalogDigest"));
        org.junit.jupiter.api.Assertions.assertEquals("catalog", params.getValue().getValue("catalogDigest"));
    }
}
