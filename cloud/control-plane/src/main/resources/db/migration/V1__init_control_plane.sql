-- 创建 MCP 租户主表，统一承载 Catalog 与 Runtime 的租户隔离身份。
CREATE TABLE mcp_tenant (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '租户记录主键',
    tenant_id VARCHAR(64) NOT NULL COMMENT '租户唯一编号，由 Platform 统一分配',
    status VARCHAR(16) NOT NULL COMMENT '租户状态：ACTIVE 表示启用，DISABLED 表示停用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_id (tenant_id(64))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Control Plane 租户主数据';

-- 创建规划 Catalog 元数据表，按租户和 Catalog Digest 保存不可覆盖的内容寻址快照。
CREATE TABLE mcp_planning_catalog (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '规划 Catalog 元数据主键',
    tenant_id VARCHAR(64) NOT NULL COMMENT 'Catalog 所属租户唯一编号',
    catalog_digest CHAR(71) NOT NULL COMMENT 'Catalog JSON 内容的 SHA-256 摘要，格式为 sha256 加十六进制摘要',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_catalog (tenant_id, catalog_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='云端规划器使用的 Runtime Catalog 元数据';

-- 创建规划 Catalog 内容表，将长文本与高频查询的 Catalog 元数据分离存储。
CREATE TABLE mcp_planning_catalog_content (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '规划 Catalog 内容主键',
    catalog_id BIGINT UNSIGNED NOT NULL COMMENT '关联的规划 Catalog 元数据主键，由应用保证关联完整性',
    catalog_json LONGTEXT NOT NULL COMMENT '供云端规划器使用的 Runtime 能力 Schema JSON',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_catalog_id (catalog_id),
    CONSTRAINT fk_catalog_content_catalog FOREIGN KEY (catalog_id)
        REFERENCES mcp_planning_catalog (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='云端规划器使用的 Runtime Catalog JSON 内容';

-- 创建 Runtime 实例表，记录租户内实例实际运行的制品版本和最后注册时间。
CREATE TABLE mcp_runtime_instance (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Runtime 实例记录主键',
    tenant_id VARCHAR(64) NOT NULL COMMENT 'Runtime 实例所属租户唯一编号',
    instance_id VARCHAR(128) NOT NULL COMMENT 'Runtime 在所属租户内稳定且唯一的实例编号',
    artifact_version VARCHAR(64) NOT NULL COMMENT 'Runtime 实际运行的 JAR 版本',
    artifact_digest CHAR(71) NOT NULL COMMENT 'Runtime 实际运行 JAR 的 SHA-256 摘要，格式为 sha256 加十六进制摘要',
    catalog_digest CHAR(71) NOT NULL COMMENT 'Runtime 当前已提交并获云端确认的 Catalog Digest',
    last_seen_time DATETIME NOT NULL COMMENT 'Runtime 最近一次成功注册或上报心跳的时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_instance_id (instance_id),
    KEY idx_tenant_seen (tenant_id, last_seen_time),
    CONSTRAINT fk_runtime_catalog FOREIGN KEY (tenant_id, catalog_digest)
        REFERENCES mcp_planning_catalog (tenant_id, catalog_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Runtime 实例实际运行状态';

-- 创建 Platform MCP 实例表，保存 Platform 下发的租户实例期望状态和发布信息。
CREATE TABLE mcp_platform_instance (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Platform MCP 实例记录主键',
    tenant_id VARCHAR(64) NOT NULL COMMENT 'Platform MCP 实例所属租户唯一编号',
    platform_mcp_id VARCHAR(64) NOT NULL COMMENT 'Platform 内租户 MCP 配置的稳定主键',
    instance_id VARCHAR(128) NOT NULL COMMENT 'Runtime 在所属租户内稳定且唯一的实例编号',
    name VARCHAR(128) NOT NULL COMMENT 'Platform 配置的 MCP 实例名称',
    desired_version VARCHAR(64) NULL COMMENT 'Platform 期望部署的 Runtime JAR 版本，未发布时为空',
    release_notes VARCHAR(1000) NULL COMMENT '期望部署版本的发布说明，未发布时为空',
    download_url VARCHAR(512) NULL COMMENT '期望部署版本的安装包下载地址，未发布时为空',
    file_hash CHAR(64) NULL COMMENT '安装包文件的 SHA-256 十六进制摘要，未发布时为空',
    status VARCHAR(16) NOT NULL COMMENT 'Platform 期望状态：ACTIVE 表示启用，DELETED 表示已删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_platform_instance (instance_id),
    UNIQUE KEY uk_platform_mcp (tenant_id, platform_mcp_id),
    KEY idx_platform_instance_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Platform 声明的 MCP 实例期望状态';
