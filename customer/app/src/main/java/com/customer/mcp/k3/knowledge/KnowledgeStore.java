package com.customer.mcp.k3.knowledge;

import com.customer.mcp.k3.client.K3Properties;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在客户 Runtime 本地保存金蝶账套知识，并定期原子写入 JSON 快照。
 *
 * <p>区分 API 客观观察、待验证猜测、用户确认三种来源：
 * 表单/字段存在与“这个表单符合用户意图”绝非同一事实。文件名由服务器和账套 ID 摘要生成，
 * 不允许不同客户账套共享知识；写入通过版本号和原子替换避免半文件。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Component
public class KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStore.class);
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<KnowledgeEntry>>() { }.getType();

    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, KnowledgeEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private final Object writeLock = new Object();
    private final Path file;
    private volatile long flushedRevision;

    /**
     * 按服务器与账套生成隔离文件，避免不同客户配置相互污染。
     *
     * @param properties 金蝶连接配置
     */
    public KnowledgeStore(K3Properties properties) {
        String identity = properties.getServerUrl() + "\n" + properties.getAcctId();
        this.file = Path.of(properties.getKnowledgeDir(), digest(identity) + ".json");
        load();
    }

    /**
     * 读取当前未过期的事实。
     *
     * @param layer 知识层
     * @param key 事实键
     * @return 事实或 null
     */
    public KnowledgeEntry get(KnowledgeEntry.Layer layer, String key) {
        KnowledgeEntry entry = entries.get(index(layer, key));
        return entry == null || entry.expired(System.currentTimeMillis()) ? null : entry;
    }

    /**
     * 返回当前账套的有效知识快照，供本地候选排序和规划摘要使用。
     *
     * @return 不可变条目列表
     */
    public List<KnowledgeEntry> snapshot() {
        long now = System.currentTimeMillis();
        return entries.values().stream().filter(entry -> !entry.expired(now)).toList();
    }

    /**
     * 记录金蝶 API 可客观证明的事实，不据此推断用户意图。
     *
     * <p>调用方必须先排除金蝶业务失败状态，包括 ExecuteBillQuery 藏在结果行中的错误。
     * 零行结果可证明请求格式被接受，但不能证明账簿/期间有数据或语义匹配。</p>
     *
     * @param layer 知识层
     * @param key 事实键
     * @param value 不含业务行数据的事实值
     * @param ttlSeconds 生存秒数，0 表示长期有效
     */
    public void observe(KnowledgeEntry.Layer layer, String key, String value, long ttlSeconds) {
        if (layer == KnowledgeEntry.Layer.TEMPLATE) {
            throw new IllegalArgumentException("Template cannot be learned from a single API response");
        }
        long now = System.currentTimeMillis();
        long expiresAt = ttlSeconds <= 0 ? 0 : now + ttlSeconds * 1000;
        put(new KnowledgeEntry(layer, key, value, KnowledgeEntry.Source.API_OBSERVATION,
                0.8, 1, null, now, expiresAt));
    }

    /**
     * 保存用户明确确认的语义映射。
     *
     * <p>只能在员工身份与权限通过、最终计划使用该确认值且执行成功后调用；
     * 不能把模型选择或 API 成功自动升级为 USER_CONFIRMATION。</p>
     *
     * @param layer 知识层
     * @param key 意图与参数上下文组成的键
     * @param value 用户选定的结构值
     * @param employeeId 确认员工编号
     * @param ttlSeconds 生存秒数
     */
    public void confirm(KnowledgeEntry.Layer layer, String key, String value,
                        Long employeeId, long ttlSeconds) {
        long now = System.currentTimeMillis();
        long expiresAt = ttlSeconds <= 0 ? 0 : now + ttlSeconds * 1000;
        put(new KnowledgeEntry(layer, key, value, KnowledgeEntry.Source.USER_CONFIRMATION,
                1.0, 1, employeeId, now, expiresAt));
    }

    /**
     * 将映射先记录为待验证候选，API 成功不能把它升级为用户意图。
     *
     * @param layer 知识层
     * @param key 意图上下文键
     * @param value 候选值
     */
    public void pending(KnowledgeEntry.Layer layer, String key, String value) {
        KnowledgeEntry existing = get(layer, key);
        if (existing != null && existing.source() == KnowledgeEntry.Source.USER_CONFIRMATION) {
            return;
        }
        long now = System.currentTimeMillis();
        put(new KnowledgeEntry(layer, key, value, KnowledgeEntry.Source.PENDING,
                0.3, 1, null, now, now + 7L * 24 * 3600 * 1000));
    }

    /**
     * 合并内存事实并增加待刷盘版本。
     *
     * @param entry 已验证的知识条目
     */
    public void put(KnowledgeEntry entry) {
        entries.put(index(entry.layer(), entry.key()), entry);
        revision.incrementAndGet();
    }

    /**
     * 合并高频写入并原子替换磁盘快照，失败时保留旧文件供下次重试。
     *
     * <p>先写同目录临时文件，再优先使用原子移动；目标文件不会暴露部分 JSON。
     * 只在替换成功后推进 flushedRevision，落盘失败会在下一次调度重试。</p>
     */
    @Scheduled(fixedDelay = 5000)
    public void flush() {
        synchronized (writeLock) {
            long targetRevision = revision.get();
            if (targetRevision == flushedRevision) {
                return;
            }
            try {
                Files.createDirectories(file.getParent());
                Path temp = Files.createTempFile(file.getParent(), "knowledge-", ".tmp");
                try {
                    List<KnowledgeEntry> current = new ArrayList<>(snapshot());
                    Files.writeString(temp, gson.toJson(current, ENTRY_LIST_TYPE), StandardCharsets.UTF_8);
                    try {
                        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                    flushedRevision = targetRevision;
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (IOException exception) {
                log.error("K3 knowledge persistence failed: path={}", file, exception);
            }
        }
    }

    /**
     * 关闭应用前保存尚未刷盘的事实。
     */
    @PreDestroy
    public void close() {
        flush();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<KnowledgeEntry> loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), ENTRY_LIST_TYPE);
            if (loaded != null) {
                for (KnowledgeEntry entry : loaded) {
                    if (entry != null && !entry.expired(Instant.now().toEpochMilli())) {
                        entries.put(index(entry.layer(), entry.key()), entry);
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.error("K3 knowledge loading failed: path={}", file, exception);
        }
    }

    private static String index(KnowledgeEntry.Layer layer, String key) {
        return layer.name() + ':' + key;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
