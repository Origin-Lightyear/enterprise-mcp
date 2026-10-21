package com.kevin.mcp.cloud.api;

/**
 * 绑定 Runtime 身份与结构知识内容，供 Control Plane 独立校验。
 *
 * @param tenantId 租户编号
 * @param instanceId Runtime 实例编号
 * @param artifactDigest 当前运行制品摘要
 * @param catalogDigest 当前能力目录摘要
 * @param knowledgeDigest 结构知识内容摘要
 * @param knowledgeJson 规范化知识 JSON
 * @author Kevin
 * @date 2026-10-14
 */
public record KnowledgeSnapshotUpload(String tenantId, String instanceId, String artifactDigest,
                                      String catalogDigest, String knowledgeDigest, String knowledgeJson) {
}
