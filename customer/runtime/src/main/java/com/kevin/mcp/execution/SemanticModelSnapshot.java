package com.kevin.mcp.execution;

import java.util.Map;

/**
 * 承载一次请求绑定的本地语义模型快照。
 *
 * @param digest MDL 内容摘要
 * @param manifest MDL 编译后的结构化内容
 * @author Kevin
 * @date 2026-09-17
 */
public record SemanticModelSnapshot(String digest, Map<String, Object> manifest) {

    /**
     * 生成不包含语义模型的空快照。
     *
     * @return 空快照
     */
    public static SemanticModelSnapshot empty() {
        return new SemanticModelSnapshot("", Map.of());
    }

    /**
     * 归一化模型内容，避免调用方修改请求级快照。
     *
     * @param digest MDL 内容摘要
     * @param manifest MDL 编译内容
     */
    public SemanticModelSnapshot {
        manifest = manifest == null ? Map.of() : Map.copyOf(manifest);
    }
}
