package com.kevin.mcp.catalog;

/**
 * 承载一次管理员发起的完整 MDL Update。
 *
 * @param profileName 管理页面中待应用的数据源建模内容
 * @param expectedMdlDigest 管理员开始编辑时看到的 MDL Digest；首次配置时为空
 * @param idempotencyKey 本次操作的稳定幂等键
 * @param operatorId 已认证管理员编号
 * @author Kevin
 * @date 2026-09-18
 */
public record MdlUpdateCommand(
        String profileName,
        String expectedMdlDigest,
        String idempotencyKey,
        String operatorId
) {
}
