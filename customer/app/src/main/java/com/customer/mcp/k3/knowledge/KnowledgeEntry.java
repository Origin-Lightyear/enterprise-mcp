package com.customer.mcp.k3.knowledge;

/**
 * 描述当前金蝶账套中一条带证据和时效边界的学习事实。
 *
 * @param layer 知识层
 * @param key 规范化事实键
 * @param value 结构化事实值，不得含业务行数据
 * @param source 证据来源
 * @param confidence 置信度，范围为 0 至 1
 * @param observations 观察计数；当前写入为 1，尚未实现跨独立会话收敛
 * @param confirmedBy 确认员工编号，仅用于审计；未确认时为空
 * @param observedAt 最近观察时间毫秒
 * @param expiresAt 过期时间毫秒，0 表示无固定过期时间
 * @author Kevin
 * @date 2026-10-14
 */
public record KnowledgeEntry(Layer layer, String key, String value, Source source,
                             double confidence, int observations, Long confirmedBy,
                             long observedAt, long expiresAt) {

    /**
     * 标识学习事实所属层，避免意图映射与客观存在性混淆。
     *
     * @author Kevin
     * @date 2026-10-14
     */
    public enum Layer {
        /** 表单或字段存在性，以及已确认的语义映射。 */
        ENTITY,
        /** 账簿等维度是否存在数据。 */
        TOPOLOGY,
        /** 已确认的参数结构。 */
        TEMPLATE,
        /** 会过期的最近期间或日期。 */
        FRESHNESS
    }

    /**
     * 标识证据的来源和可用于决策的信任级别。
     *
     * @author Kevin
     * @date 2026-10-14
     */
    public enum Source {
        /** 仅由金蝶 API 证明客观存在。 */
        API_OBSERVATION,
        /** 用户通过 Elicitation 明确选择。 */
        USER_CONFIRMATION,
        /** 为跨独立会话重复收敛预留；当前版本不产生此来源。 */
        SESSION_CONVERGENCE,
        /** 仍需验证的意图映射候选。 */
        PENDING
    }

    /**
     * 拒绝无效知识，防止损坏的持久化文件污染规划。
     */
    public KnowledgeEntry {
        if (layer == null || source == null || key == null || key.isBlank() || value == null
                || !Double.isFinite(confidence) || confidence < 0 || confidence > 1
                || observations < 0 || observedAt < 0 || expiresAt < 0) {
            throw new IllegalArgumentException("Invalid K3 knowledge entry");
        }
        confirmedBy = confirmedBy == null ? null : confirmedBy;
    }

    /**
     * 判断事实是否已过期。
     *
     * @param now 当前时间毫秒
     * @return 已超过有效期时返回 true
     */
    public boolean expired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }
}
