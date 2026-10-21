-- 创建按租户、Runtime 实例和摘要寻址的知识快照表，避免高频学习事实改写不可变能力 Catalog。
CREATE TABLE mcp_planning_knowledge_snapshot (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '知识快照主键',
    tenant_id VARCHAR(64) NOT NULL COMMENT '知识所属租户编号',
    instance_id VARCHAR(128) NOT NULL COMMENT '产生知识的 Runtime 实例编号',
    catalog_digest CHAR(71) NOT NULL COMMENT '上传时绑定的 Runtime 能力 Catalog 摘要',
    knowledge_digest CHAR(71) NOT NULL COMMENT '规范化知识 JSON 的 SHA-256 内容摘要',
    knowledge_json LONGTEXT NOT NULL COMMENT '仅含表单、字段及参数结构提示的规划知识 JSON',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '快照创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '快照最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_identity (tenant_id, instance_id, catalog_digest, knowledge_digest),
    KEY idx_knowledge_retention (tenant_id, instance_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户 Runtime 上报的金蝶规划知识快照';
