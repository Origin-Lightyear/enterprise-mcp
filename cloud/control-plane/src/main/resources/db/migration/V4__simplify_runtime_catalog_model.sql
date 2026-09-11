-- 删除不再参与规划选择的目录版本和状态字段，JAR 摘要成为唯一版本绑定依据。
ALTER TABLE mcp_planning_catalog DROP INDEX uk_tenant_catalog;
ALTER TABLE mcp_planning_catalog DROP INDEX idx_tenant_status;
ALTER TABLE mcp_planning_catalog DROP COLUMN catalog_version;
ALTER TABLE mcp_planning_catalog DROP COLUMN status;

-- 为同一租户的每个 JAR 摘要建立唯一 Catalog 快照。
ALTER TABLE mcp_planning_catalog ADD UNIQUE KEY uk_tenant_artifact (tenant_id, artifact_digest);

-- 删除重复保存能力编号版本的运行时快照表，完整能力已保存在 catalog_json。
DROP TABLE mcp_runtime_capability;

-- 删除仅服务于旧 DRAFT/ACTIVE/RETIRED 状态机的发布记录表。
DROP TABLE mcp_catalog_publish_record;
