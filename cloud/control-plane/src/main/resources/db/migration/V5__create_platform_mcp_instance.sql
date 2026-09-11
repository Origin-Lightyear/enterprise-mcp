-- 保存 Platform 声明的 MCP 实例期望状态，作为部署和升级的控制面事实来源。
CREATE TABLE mcp_platform_instance (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Platform MCP实例记录主键',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户唯一编号',
    platform_mcp_id VARCHAR(64) NOT NULL COMMENT 'Platform MCP主键',
    instance_id VARCHAR(128) NOT NULL COMMENT '系统生成的Runtime实例编号',
    name VARCHAR(128) NOT NULL COMMENT 'MCP名称',
    desired_version VARCHAR(64) NULL COMMENT 'Platform期望部署版本',
    release_notes VARCHAR(1000) NULL COMMENT '期望版本说明',
    download_url VARCHAR(512) NULL COMMENT '安装包下载地址',
    file_hash CHAR(64) NULL COMMENT '安装包SHA-256',
    status VARCHAR(16) NOT NULL COMMENT '期望状态：ACTIVE启用，DELETED已删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_instance (instance_id),
    UNIQUE KEY uk_platform_mcp (tenant_id, platform_mcp_id),
    KEY idx_platform_instance_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Platform声明的MCP实例期望状态';
