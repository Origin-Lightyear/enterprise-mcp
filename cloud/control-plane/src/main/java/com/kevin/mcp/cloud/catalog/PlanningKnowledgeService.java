package com.kevin.mcp.cloud.catalog;

import com.kevin.mcp.catalog.CatalogSnapshotCodec;
import com.kevin.mcp.cloud.api.KnowledgeSnapshotUpload;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 按 Runtime 身份和内容摘要保存规划知识，禁止覆盖已有快照。
 *
 * <p>知识快照不属于不可变能力 Catalog；但读取仍须同时匹配租户、实例、
 * Catalog 摘要及知识摘要。同一结构内容可在不同 Catalog 版本出现，
 * 因而唯一键和查询条件都必须包含 catalog_digest，不能只凭内容哈希跨版本复用。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Service
@Profile("control-plane")
public class PlanningKnowledgeService {

    private static final int MAX_JSON_LENGTH = 100_000;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PlanningCatalogService catalogService;

    /**
     * 注入持久化访问和 Runtime 注册校验。
     *
     * @param jdbcTemplate 命名参数 JDBC 模板
     * @param catalogService Runtime 当前 Catalog 注册状态
     */
    public PlanningKnowledgeService(NamedParameterJdbcTemplate jdbcTemplate,
                                    PlanningCatalogService catalogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogService = catalogService;
    }

    /**
     * 确认并保存客户上传的结构知识，摘要不匹配或 Runtime 未注册时失败关闭。
     *
     * <p>先用当前 Runtime 注册绑定校验身份，再对规范化 JSON 重算摘要；
     * 已存在的不可变快照不得覆盖，并发插入后再次读取比较内容。</p>
     *
     * @param upload 上传的结构知识快照
     * @return 已确认的知识摘要
     */
    public Map<String, String> upload(KnowledgeSnapshotUpload upload) {
        if (upload == null || blank(upload.tenantId()) || blank(upload.instanceId())
                || blank(upload.artifactDigest()) || blank(upload.catalogDigest())
                || blank(upload.knowledgeDigest()) || blank(upload.knowledgeJson())
                || upload.knowledgeJson().length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("Knowledge snapshot identity or content is invalid");
        }
        requireRegistration(upload.tenantId(), upload.instanceId(), upload.artifactDigest(),
                upload.catalogDigest());
        String canonical = CatalogSnapshotCodec.canonicalize(upload.knowledgeJson());
        if (!CatalogSnapshotCodec.digest(canonical).equals(upload.knowledgeDigest())) {
            throw new IllegalArgumentException("Knowledge snapshot digest does not match content");
        }
        MapSqlParameterSource params = params(upload.tenantId(), upload.instanceId(),
                upload.catalogDigest(), upload.knowledgeDigest()).addValue("knowledgeJson", canonical);
        String existing = find(upload.tenantId(), upload.instanceId(),
                upload.catalogDigest(), upload.knowledgeDigest());
        if (existing != null) {
            if (!existing.equals(canonical)) {
                throw new IllegalStateException("Knowledge digest is bound to different content");
            }
            return Map.of("knowledgeDigest", upload.knowledgeDigest());
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO mcp_planning_knowledge_snapshot
                    (tenant_id, instance_id, catalog_digest, knowledge_digest, knowledge_json)
                    VALUES (:tenantId, :instanceId, :catalogDigest, :knowledgeDigest, :knowledgeJson)
                    """, params);
        } catch (DuplicateKeyException exception) {
            if (!canonical.equals(find(upload.tenantId(), upload.instanceId(),
                    upload.catalogDigest(), upload.knowledgeDigest()))) {
                throw new IllegalStateException("Knowledge snapshot changed concurrently", exception);
            }
        }
        return Map.of("knowledgeDigest", upload.knowledgeDigest());
    }

    /**
     * 读取与当前 Runtime 身份绑定的精确知识快照。
     *
     * <p>请求里的摘要是绑定，不是模糊搜索提示；找不到精确快照时失败关闭，
     * 不能退回旧 Catalog 的知识继续生成计划。</p>
     *
     * @param tenantId 租户编号
     * @param instanceId Runtime 实例编号
     * @param artifactDigest 制品摘要
     * @param catalogDigest Catalog 摘要
     * @param knowledgeDigest 知识摘要
     * @return 规划知识 JSON
     */
    public String require(String tenantId, String instanceId, String artifactDigest,
                          String catalogDigest, String knowledgeDigest) {
        requireRegistration(tenantId, instanceId, artifactDigest, catalogDigest);
        String json = find(tenantId, instanceId, catalogDigest, knowledgeDigest);
        if (json == null) {
            throw new IllegalStateException("KNOWLEDGE_DIGEST_NOT_FOUND");
        }
        return json;
    }

    private String find(String tenantId, String instanceId, String catalogDigest, String digest) {
        return jdbcTemplate.query("""
                SELECT knowledge_json FROM mcp_planning_knowledge_snapshot
                WHERE tenant_id = :tenantId AND instance_id = :instanceId
                  AND catalog_digest = :catalogDigest AND knowledge_digest = :knowledgeDigest
                """, params(tenantId, instanceId, catalogDigest, digest),
                (row, index) -> row.getString("knowledge_json")).stream().findFirst().orElse(null);
    }

    private void requireRegistration(String tenantId, String instanceId, String artifactDigest,
                                     String catalogDigest) {
        if (catalogService.findRegistration(tenantId, instanceId, artifactDigest, catalogDigest).isEmpty()) {
            throw new SecurityException("Runtime registration does not match knowledge snapshot");
        }
    }

    private static MapSqlParameterSource params(String tenantId, String instanceId,
                                                String catalogDigest, String digest) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("instanceId", instanceId).addValue("catalogDigest", catalogDigest)
                .addValue("knowledgeDigest", digest);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
