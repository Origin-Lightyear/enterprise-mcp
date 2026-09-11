-- 将 Platform 期望实例编号提升为云端全局唯一，避免不同租户复用同一 Runtime 身份。
ALTER TABLE mcp_platform_instance DROP INDEX uk_platform_instance;
ALTER TABLE mcp_platform_instance ADD UNIQUE KEY uk_platform_instance (instance_id);

-- Runtime 实际实例与 Platform 期望实例使用同一全局身份约束，防止跨租户串线。
ALTER TABLE mcp_runtime_instance DROP INDEX uk_tenant_instance;
ALTER TABLE mcp_runtime_instance ADD UNIQUE KEY uk_runtime_instance_id (instance_id);
