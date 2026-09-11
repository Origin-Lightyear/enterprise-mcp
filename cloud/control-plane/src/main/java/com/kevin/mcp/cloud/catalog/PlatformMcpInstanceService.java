package com.kevin.mcp.cloud.catalog;

import com.kevin.mcp.cloud.api.PlatformMcpInstanceSyncRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 保存 Platform 声明的 MCP 实例期望状态，供部署和升级流程校验。
 *
 * @author Kevin
 * @date 2026-09-11
 */
@Service
@Profile("control-plane")
public class PlatformMcpInstanceService {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /** 注入云端数据库访问器。 */
    public PlatformMcpInstanceService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按租户全量同步期望实例，缺失记录仅标记删除以保留审计。 */
    @Transactional
    public void sync(PlatformMcpInstanceSyncRequest request) {
        if (request == null || request.tenantId() == null || request.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        List<PlatformMcpInstanceSyncRequest.Item> items = request.servers() == null ? List.of() : request.servers();
        for (PlatformMcpInstanceSyncRequest.Item item : items) {
            if (item == null || item.platformMcpId() == null || blank(item.instanceId())) {
                throw new IllegalArgumentException("platform MCP identity is required");
            }
            jdbcTemplate.update("""
                    INSERT INTO mcp_platform_instance
                        (tenant_id, platform_mcp_id, instance_id, name, desired_version, release_notes,
                         download_url, file_hash, status, create_time, update_time)
                    VALUES (:tenantId, :platformMcpId, :instanceId, :name, :desiredVersion, :releaseNotes,
                            :downloadUrl, :fileHash, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE platform_mcp_id = VALUES(platform_mcp_id), name = VALUES(name),
                        desired_version = VALUES(desired_version), release_notes = VALUES(release_notes),
                        download_url = VALUES(download_url), file_hash = VALUES(file_hash), status = 'ACTIVE',
                        update_time = CURRENT_TIMESTAMP
                    """, params(request.tenantId(), item));
        }
        MapSqlParameterSource deleteParameters = new MapSqlParameterSource().addValue("tenantId", request.tenantId());
        if (items.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE mcp_platform_instance SET status = 'DELETED', update_time = CURRENT_TIMESTAMP
                    WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                    """, deleteParameters);
        } else {
            jdbcTemplate.update("""
                    UPDATE mcp_platform_instance SET status = 'DELETED', update_time = CURRENT_TIMESTAMP
                    WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                      AND instance_id NOT IN (:instanceIds)
                    """, deleteParameters.addValue("instanceIds", items.stream()
                    .map(PlatformMcpInstanceSyncRequest.Item::instanceId).toList()));
        }
    }

    private MapSqlParameterSource params(String tenantId, PlatformMcpInstanceSyncRequest.Item item) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("platformMcpId", item.platformMcpId()).addValue("instanceId", item.instanceId())
                .addValue("name", item.name()).addValue("desiredVersion", item.latestVersion())
                .addValue("releaseNotes", item.releaseNotes()).addValue("downloadUrl", item.downloadUrl())
                .addValue("fileHash", item.fileHash());
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
