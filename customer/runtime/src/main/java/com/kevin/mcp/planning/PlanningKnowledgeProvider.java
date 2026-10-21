package com.kevin.mcp.planning;

/**
 * 提供客户本地知识摘要的当前云端绑定，供规划请求按摘要精确引用。
 *
 * @author Kevin
 * @date 2026-10-14
 */
public interface PlanningKnowledgeProvider {

    /**
     * 确保当前知识快照已上传，并返回可由云端精确解析的摘要。
     *
     * @return 摘要；未启用知识层时返回空串
     */
    String currentDigest();
}
