-- 创建客户主表，统一承载跨 Catalog 与 Runtime 的客户隔离键。
CREATE TABLE mcp_customer (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '客户主键',
    customer_code VARCHAR(64) NOT NULL COMMENT '客户唯一编号，由受控发布流程分配',
    status VARCHAR(16) NOT NULL COMMENT '客户状态：ACTIVE 启用，DISABLED 停用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_code (customer_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 云端客户';

-- 创建规划目录元数据表，客户编号与目录版本共同保证发布版本不可覆盖。
CREATE TABLE mcp_planning_catalog (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '规划目录主键',
    customer_code VARCHAR(64) NOT NULL COMMENT '客户唯一编号',
    catalog_version VARCHAR(64) NOT NULL COMMENT '规划目录版本',
    artifact_version VARCHAR(64) NOT NULL COMMENT '客户 Runtime JAR 版本',
    artifact_digest CHAR(71) NOT NULL COMMENT '客户 Runtime JAR 的 SHA-256 摘要',
    execution_manifest_digest CHAR(71) NOT NULL COMMENT '客户本地执行清单的 SHA-256 摘要',
    catalog_digest CHAR(71) NOT NULL COMMENT '规划目录 JSON 的 SHA-256 摘要',
    status VARCHAR(16) NOT NULL COMMENT '发布状态：DRAFT 草稿，ACTIVE 已激活，RETIRED 已退役',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_catalog (customer_code, catalog_version),
    KEY idx_customer_status (customer_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 客户规划目录';

-- 创建规划目录内容表，将长文本与高频查询的目录元数据分离。
CREATE TABLE mcp_planning_catalog_content (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '规划目录内容主键',
    catalog_id BIGINT UNSIGNED NOT NULL COMMENT '规划目录主键',
    catalog_json LONGTEXT NOT NULL COMMENT '供云端规划器使用的客户能力 Schema JSON',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_catalog_id (catalog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 客户规划目录内容';

-- 创建 Runtime 实例表，按客户和实例保存实际部署版本及最后注册时间。
CREATE TABLE mcp_runtime_instance (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Runtime 实例主键',
    customer_code VARCHAR(64) NOT NULL COMMENT '客户唯一编号',
    instance_id VARCHAR(128) NOT NULL COMMENT '客户环境中的 Runtime 实例编号',
    artifact_version VARCHAR(64) NOT NULL COMMENT 'Runtime 实际部署的 JAR 版本',
    artifact_digest CHAR(71) NOT NULL COMMENT 'Runtime 实际部署 JAR 的 SHA-256 摘要',
    execution_manifest_digest CHAR(71) NOT NULL COMMENT 'Runtime 实际执行清单的 SHA-256 摘要',
    last_seen_time DATETIME NOT NULL COMMENT 'Runtime 最近一次成功注册时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_instance (customer_code, instance_id),
    KEY idx_customer_seen (customer_code, last_seen_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 客户 Runtime 实例';

-- 创建 Runtime 能力表，保存每个实例实际注册的稳定能力编号和版本。
CREATE TABLE mcp_runtime_capability (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Runtime 能力主键',
    runtime_instance_id BIGINT UNSIGNED NOT NULL COMMENT 'Runtime 实例主键',
    capability_id VARCHAR(128) NOT NULL COMMENT '跨云端与本地稳定的能力编号',
    capability_version INT UNSIGNED NOT NULL COMMENT '能力契约版本',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_instance_capability (runtime_instance_id, capability_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Runtime 实际能力';

-- 创建目录发布记录表，保留创建、激活和退役状态转换的追溯证据。
CREATE TABLE mcp_catalog_publish_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '发布记录主键',
    customer_code VARCHAR(64) NOT NULL COMMENT '客户唯一编号',
    catalog_version VARCHAR(64) NOT NULL COMMENT '规划目录版本',
    action VARCHAR(16) NOT NULL COMMENT '发布动作：CREATE、ACTIVATE、RETIRE',
    from_status VARCHAR(16) NULL COMMENT '转换前状态，创建时为空',
    to_status VARCHAR(16) NOT NULL COMMENT '转换后状态',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_customer_catalog_time (customer_code, catalog_version, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 规划目录发布记录';
