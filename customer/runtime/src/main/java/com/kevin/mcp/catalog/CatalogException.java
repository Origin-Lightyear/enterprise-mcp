package com.kevin.mcp.catalog;

/**
 * 表示 Runtime Catalog module 的稳定业务失败。
 *
 * @author Kevin
 * @date 2026-09-18
 */
public class CatalogException extends RuntimeException {

    private final Code code;

    /**
     * 创建不含底层异常的 Catalog 失败。
     *
     * @param code 稳定错误码
     * @param message 安全错误说明
     */
    public CatalogException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 创建保留底层原因的 Catalog 失败。
     *
     * @param code 稳定错误码
     * @param message 安全错误说明
     * @param cause 底层原因
     */
    public CatalogException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public Code code() {
        return code;
    }

    /**
     * 表示 Runtime Catalog 操作的可判定失败类型。
     *
     * @author Kevin
     * @date 2026-09-18
     */
    public enum Code {

        /** 候选 MDL 不完整或不能构建。 */
        VALIDATION_FAILED,

        /** 管理员基于过期 Current MDL 提交。 */
        CONCURRENT_UPDATE,

        /** 同一幂等键被用于不同内容。 */
        IDEMPOTENCY_CONFLICT,

        /** Control Plane 在要求的同步阶段不可用。 */
        CONTROL_PLANE_UNAVAILABLE,

        /** Runtime 与 Control Plane 计算的摘要不一致。 */
        DIGEST_MISMATCH,

        /** Runtime 当前未达到规划就绪状态。 */
        NOT_READY,

        /** 执行计划绑定的 Catalog 已不是 Current Catalog。 */
        STALE_PLAN,

        /** Current MDL 已提交，但云端当前引用仍需恢复。 */
        REFERENCE_FINALIZATION_PENDING
    }
}
