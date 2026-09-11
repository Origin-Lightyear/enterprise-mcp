-- 将客户主表改名为租户主表，统一云端身份模型。
RENAME TABLE mcp_customer TO mcp_tenant;

-- 将租户主表的身份列改为 tenant_id，并更新列注释。
ALTER TABLE mcp_tenant CHANGE COLUMN customer_code tenant_id VARCHAR(64) NOT NULL COMMENT '租户唯一编号';

-- 重命名租户主表唯一索引，避免遗留 customer 语义。
ALTER TABLE mcp_tenant RENAME INDEX uk_customer_code TO uk_tenant_id;

-- 将规划目录的隔离列改为 tenant_id。
ALTER TABLE mcp_planning_catalog CHANGE COLUMN customer_code tenant_id VARCHAR(64) NOT NULL COMMENT '租户唯一编号';

-- 重命名规划目录索引，使索引语义与租户字段一致。
ALTER TABLE mcp_planning_catalog RENAME INDEX uk_customer_catalog TO uk_tenant_catalog;

-- 重命名规划目录状态索引，使索引语义与租户字段一致。
ALTER TABLE mcp_planning_catalog RENAME INDEX idx_customer_status TO idx_tenant_status;

-- 将 Runtime 实例的隔离列改为 tenant_id。
ALTER TABLE mcp_runtime_instance CHANGE COLUMN customer_code tenant_id VARCHAR(64) NOT NULL COMMENT '租户唯一编号';

-- 重命名 Runtime 实例唯一索引，使实例在租户内保持唯一。
ALTER TABLE mcp_runtime_instance RENAME INDEX uk_customer_instance TO uk_tenant_instance;

-- 重命名 Runtime 实例查询索引，使索引语义与租户字段一致。
ALTER TABLE mcp_runtime_instance RENAME INDEX idx_customer_seen TO idx_tenant_seen;

-- 将目录发布记录的隔离列改为 tenant_id。
ALTER TABLE mcp_catalog_publish_record CHANGE COLUMN customer_code tenant_id VARCHAR(64) NOT NULL COMMENT '租户唯一编号';

-- 重命名目录发布记录索引，使索引语义与租户字段一致。
ALTER TABLE mcp_catalog_publish_record RENAME INDEX idx_customer_catalog_time TO idx_tenant_catalog_time;
