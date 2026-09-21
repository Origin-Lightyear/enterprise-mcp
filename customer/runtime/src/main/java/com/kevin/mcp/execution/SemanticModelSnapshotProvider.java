package com.kevin.mcp.execution;

/**
 * 提供客户本地语义模型的不可变请求快照。
 *
 * @author Kevin
 * @date 2026-09-17
 */
public interface SemanticModelSnapshotProvider {

    /**
     * 判断当前部署是否启用语义模型能力。
     *
     * @return 启用时返回 true；未启用时 Catalog 只上传固定能力 Schema
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 读取当前 MDL 内容和摘要。
     *
     * <p>实现必须从同一次 Adapter 响应读取摘要和模型，避免并发重建时产生混合版本。</p>
     *
     * @return 当前语义模型快照
     */
    SemanticModelSnapshot snapshot();
}
