package com.kevin.mcp.export;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 导出下载会话注册表；短码是一次性凭证，下载成功或过期后立即失效。
 *
 * <p>注册表只保存在内存中，进程重启后由清理任务按文件修改时间删除孤儿导出文件，
 * 不需要持久化恢复。短码校验失败统一返回空，调用方必须映射为 404 防止探测。</p>
 *
 * @author Kevin
 * @date 2026-10-19
 */
@Component
public class ExportSessionRegistry {

    /** 短码到会话的并发映射；导出量受磁盘上限约束，无需淘汰策略。 */
    private final ConcurrentHashMap<String, ExportSession> sessions = new ConcurrentHashMap<>();

    /**
     * 注册一次导出会话。
     *
     * @param session 已构造完成的导出会话
     */
    public void register(ExportSession session) {
        sessions.put(session.fileId(), session);
    }

    /**
     * 消费一次性短码；仅当未过期且未下载过时返回会话，并标记已下载。
     *
     * @param fileId 下载短码
     * @return 命中的导出会话；不存在、过期或已下载时为空
     */
    public Optional<ExportSession> consume(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return Optional.empty();
        }
        ExportSession session = sessions.get(fileId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.downloaded() || !Instant.now().isBefore(session.downloadExpiresAt())) {
            sessions.remove(fileId);
            return Optional.empty();
        }
        if (!sessions.replace(fileId, session, markDownloaded(session))) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * 按短码查询会话，供清理任务判断文件是否仍被引用。
     *
     * @param fileId 下载短码
     * @return 命中的导出会话
     */
    public Optional<ExportSession> find(String fileId) {
        return Optional.ofNullable(sessions.get(fileId));
    }

    /**
     * 移除过期会话；返回仍存活的会话列表供文件清理使用。
     *
     * @return 未过期的导出会话
     */
    public Map<String, ExportSession> removeExpired() {
        sessions.values().removeIf(session ->
                Instant.now().isAfter(session.fileExpiresAt()));
        return Map.copyOf(sessions);
    }

    /**
     * 复制原始会话并标记已下载，保证并发下载只允许成功一次。
     *
     * @param session 原始会话
     * @return 已下载状态的会话
     */
    private ExportSession markDownloaded(ExportSession session) {
        return new ExportSession(session.fileId(), session.filePath(), session.fileName(),
                session.queryId(), session.downloadExpiresAt(), session.fileExpiresAt(), true);
    }

    /**
     * 一次导出下载会话。
     *
     * @param fileId 下载短码
     * @param filePath 服务器文件绝对路径
     * @param fileName 建议客户端保存的文件名
     * @param queryId 业务查询标识
     * @param downloadExpiresAt 短码过期时间
     * @param fileExpiresAt 服务器文件过期时间
     * @param downloaded 是否已被下载
     *
     * @author Kevin
     * @date 2026-10-19
     */
    public record ExportSession(
            String fileId,
            String filePath,
            String fileName,
            String queryId,
            Instant downloadExpiresAt,
            Instant fileExpiresAt,
            boolean downloaded
    ) {
    }
}