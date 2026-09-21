package com.kevin.mcp.catalog;

import com.kevin.mcp.execution.SemanticModelSnapshot;

/**
 * 承载 Wren adapter 已完整校验但尚未提交的候选 MDL。
 *
 * @param candidateToken 短生命周期候选标识，不代表 Release 或版本
 * @param snapshot 候选 MDL 不可变快照
 * @author Kevin
 * @date 2026-09-18
 */
public record PreparedMdl(String candidateToken, SemanticModelSnapshot snapshot) {
}
