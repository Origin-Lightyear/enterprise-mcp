package com.kevin.mcp.catalog;

/**
 * 承载一次管理员发起的完整 MDL Update。
 *
 * @param profileName 管理页面中待应用的数据源建模内容
 * @param expectedMdlDigest 管理员开始编辑时看到的 MDL Digest；首次配置时为空
 * @param idempotencyKey 本次操作的稳定幂等键
 * @param operatorId 已认证管理员编号
 * @param deleteDatasource 是否同时移除该数据源的连接、草稿与生效模型
 * @author Kevin
 * @date 2026-09-18
 */
public record MdlUpdateCommand(
        String profileName,
        String expectedMdlDigest,
        String idempotencyKey,
        String operatorId,
        boolean deleteDatasource
) {
    /**
     * 构造普通建模更新，保持现有调用方的更新语义。
     *
     * @param profileName 待应用的数据源
     * @param expectedMdlDigest 预期旧摘要
     * @param idempotencyKey 操作幂等键
     * @param operatorId 已认证管理员编号
     */
    public MdlUpdateCommand(String profileName, String expectedMdlDigest, String idempotencyKey, String operatorId) {
        this(profileName, expectedMdlDigest, idempotencyKey, operatorId, false);
    }
}
