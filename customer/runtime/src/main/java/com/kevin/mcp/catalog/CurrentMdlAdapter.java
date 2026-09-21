package com.kevin.mcp.catalog;

import com.kevin.mcp.execution.SemanticModelSnapshot;

import java.util.Optional;

/**
 * 隔离 Runtime Catalog module 与本机 Wren 进程的内部 seam。
 *
 * @author Kevin
 * @date 2026-09-18
 */
public interface CurrentMdlAdapter {

    /**
     * 读取同一次锁内捕获的 Current MDL 快照。
     *
     * @return Current MDL；尚未配置时为空
     */
    Optional<SemanticModelSnapshot> current();

    /**
     * 完整校验并固化一个不可变候选 MDL，但不得改变 Current MDL。
     *
     * @param profileName 待应用的数据源建模内容
     * @return 已准备候选
     */
    PreparedMdl prepare(String profileName);

    /**
     * 以乐观并发方式原子提交候选为 Current MDL。
     *
     * @param candidateToken 候选标识
     * @param expectedMdlDigest 预期旧 MDL Digest；首次配置时为空
     * @return 提交后的 Current MDL 快照
     */
    SemanticModelSnapshot commit(String candidateToken, String expectedMdlDigest);

    /**
     * 丢弃未提交候选；候选不存在时必须幂等成功。
     *
     * @param candidateToken 候选标识
     */
    void discard(String candidateToken);
}
