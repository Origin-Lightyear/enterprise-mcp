-- 移除规划目录中的重复执行清单摘要，Catalog Schema 与 artifact_digest 作为唯一发布依据。
ALTER TABLE mcp_planning_catalog DROP COLUMN execution_manifest_digest;

-- 移除 Runtime 注册中的重复执行清单摘要，仅保留实际 JAR 摘要。
ALTER TABLE mcp_runtime_instance DROP COLUMN execution_manifest_digest;
