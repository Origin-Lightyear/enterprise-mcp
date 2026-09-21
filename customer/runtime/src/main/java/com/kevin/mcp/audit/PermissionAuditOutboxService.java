package com.kevin.mcp.audit;

import com.google.gson.reflect.TypeToken;
import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 持久化并重试向 Tenant 上报权限审计摘要。
 *
 * <p>每个 {@code auditId} 对应一个独立文件，Tenant 可据此实现幂等。网络失败只保留待发送记录，
 * 不阻断已经完成本地强制审计的只读查询。</p>
 *
 * @author Kevin
 * @date 2026-09-17
 */
@Service
public class PermissionAuditOutboxService {

    private static final Logger log = LoggerFactory.getLogger(PermissionAuditOutboxService.class);
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final HttpAgentServerClient agentServerClient;
    private final Path outboxDirectory;
    private final Object outboxLock = new Object();

    /**
     * 创建权限审计 Outbox。
     *
     * @param agentServerClient Tenant 客户端
     * @param outboxDirectory 待上报文件目录
     */
    public PermissionAuditOutboxService(
            HttpAgentServerClient agentServerClient,
            @Value("${enterprise.audit.outbox-directory:./data/permission-audit-outbox}") String outboxDirectory
    ) {
        this.agentServerClient = agentServerClient;
        this.outboxDirectory = Path.of(outboxDirectory).toAbsolutePath().normalize();
    }

    /**
     * 将安全审计摘要持久化后立即尝试上报。
     *
     * @param auditSummary 不含敏感正文的审计摘要
     * @return 全局唯一审计编号
     */
    public String enqueue(Map<String, Object> auditSummary) {
        String auditId = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("auditId", auditId);
        payload.put("createdAt", Instant.now().toString());
        if (auditSummary != null) {
            payload.putAll(auditSummary);
        }
        synchronized (outboxLock) {
            persist(auditId, payload);
            flushLocked();
        }
        return auditId;
    }

    /** 定时重试尚未成功上报的权限审计。 */
    @Scheduled(fixedDelayString = "${enterprise.audit.retry-delay:30s}")
    public void retryPending() {
        synchronized (outboxLock) {
            flushLocked();
        }
    }

    private void persist(String auditId, Map<String, Object> payload) {
        try {
            Files.createDirectories(outboxDirectory);
            Path target = outboxDirectory.resolve(auditId + ".json");
            Path temporary = outboxDirectory.resolve(auditId + ".tmp");
            Files.writeString(temporary, GsonUtil.toJson(payload), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Permission audit outbox persistence failed", exception);
        }
    }

    private void flushLocked() {
        List<Path> pendingFiles;
        try {
            if (!Files.isDirectory(outboxDirectory)) {
                return;
            }
            try (var paths = Files.list(outboxDirectory)) {
                pendingFiles = paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
        } catch (IOException exception) {
            log.warn("读取权限审计 Outbox 失败: type={}", exception.getClass().getSimpleName());
            return;
        }
        for (Path pendingFile : pendingFiles) {
            try {
                Map<String, Object> payload = GsonUtil.fromJson(
                        Files.readString(pendingFile, StandardCharsets.UTF_8), MAP_TYPE);
                if (payload == null || payload.get("auditId") == null) {
                    throw new IllegalStateException("Permission audit outbox payload is invalid");
                }
                agentServerClient.reportPermissionAudit(payload);
                Files.delete(pendingFile);
            } catch (Exception exception) {
                log.warn("权限审计暂未上报，将在下个周期重试: type={}",
                        exception.getClass().getSimpleName());
                return;
            }
        }
    }
}
