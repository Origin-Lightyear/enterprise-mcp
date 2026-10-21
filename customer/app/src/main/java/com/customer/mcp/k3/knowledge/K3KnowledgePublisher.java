package com.customer.mcp.k3.knowledge;

import com.customer.mcp.k3.client.K3Properties;
import com.kevin.mcp.agent.client.PlanningGatewayClient;
import com.kevin.mcp.agent.configuration.RuntimeArtifactDigest;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.catalog.CatalogSnapshotCodec;
import com.kevin.mcp.catalog.PlanningReadinessStatus;
import com.kevin.mcp.catalog.RuntimeCatalog;
import com.kevin.mcp.planning.PlanningKnowledgeProvider;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将筛选后的金蝶结构知识上传到 Control Plane，并按内容摘要绑定规划请求。
 *
 * <p>与源项目的本地学习缓存不同，云端只接收结构事实而非业务行、凭证或完整查询结果。
 * 知识摘要与能力 Catalog 摘要分离：学习事实频繁变化时不重建不可变能力 Catalog，
 * 但每份知识仍绑定上传时的 Catalog，避免跨版本误用。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Component
@ConditionalOnProperty(name = "enterprise.k3.enabled", havingValue = "true")
public class K3KnowledgePublisher implements PlanningKnowledgeProvider {

    private static final Logger log = LoggerFactory.getLogger(K3KnowledgePublisher.class);
    private final KnowledgeStore knowledge;
    private final K3Properties properties;
    private final RuntimeCatalog runtimeCatalog;
    private final RuntimeIdentityProperties identity;
    private final RuntimeArtifactDigest artifactDigest;
    private final PlanningGatewayClient gateway;
    private final Object uploadLock = new Object();
    private volatile String uploadedDigest = "";
    private volatile String uploadedCatalogDigest = "";

    /**
     * 注入结构知识和当前 Runtime 注册身份。
     *
     * @param knowledge 客户本地知识
     * @param properties 金蝶启用配置
     * @param runtimeCatalog 当前能力目录
     * @param identity Runtime 身份
     * @param artifactDigest 运行制品摘要
     * @param gateway 云端规划网关
     */
    public K3KnowledgePublisher(KnowledgeStore knowledge, K3Properties properties, RuntimeCatalog runtimeCatalog,
                                RuntimeIdentityProperties identity, RuntimeArtifactDigest artifactDigest,
                                PlanningGatewayClient gateway) {
        this.knowledge = knowledge;
        this.properties = properties;
        this.runtimeCatalog = runtimeCatalog;
        this.identity = identity;
        this.artifactDigest = artifactDigest;
        this.gateway = gateway;
    }

    /**
     * 在首次规划前同步知识，并在 Catalog 未就绪时保留原有失败关闭行为。
     *
     * @return 已获云端确认的知识摘要
     */
    @Override
    public String currentDigest() {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("K3 is enabled but connection is not configured");
        }
        if (runtimeCatalog.current().readiness() != PlanningReadinessStatus.READY) {
            throw new IllegalStateException("Runtime Catalog is not ready for K3 knowledge upload");
        }
        String catalogDigest = runtimeCatalog.current().catalogDigest();
        List<Map<String, Object>> hints = buildHints();
        // Canonical JSON 决定内容摘要；未收到云端确认前不能把本地摘要用于规划请求。
        String canonical = CatalogSnapshotCodec.canonicalize(GsonUtil.toJson(Map.of("hints", hints)));
        String digest = CatalogSnapshotCodec.digest(canonical);
        if (digest.equals(uploadedDigest) && catalogDigest.equals(uploadedCatalogDigest)) {
            return digest;
        }
        synchronized (uploadLock) {
            if (digest.equals(uploadedDigest) && catalogDigest.equals(uploadedCatalogDigest)) {
                return digest;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tenantId", identity.getTenantId());
            payload.put("instanceId", identity.getInstanceId());
            payload.put("artifactDigest", artifactDigest.getValue());
            payload.put("catalogDigest", catalogDigest);
            payload.put("knowledgeDigest", digest);
            payload.put("knowledgeJson", canonical);
            Map<?, ?> response = gateway.uploadKnowledge(payload);
            if (!digest.equals(response.get("knowledgeDigest"))) {
                throw new IllegalStateException("Control Plane did not confirm K3 knowledge digest");
            }
            uploadedDigest = digest;
            uploadedCatalogDigest = catalogDigest;
            return digest;
        }
    }

    /**
     * 在 Catalog READY 后主动预热知识快照，运行中按去抖窗口同步变化。
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 10000)
    public void synchronize() {
        if (runtimeCatalog.current().readiness() != PlanningReadinessStatus.READY) {
            return;
        }
        try {
            currentDigest();
        } catch (RuntimeException exception) {
            log.warn("K3 knowledge upload pending: reason={}", exception.getClass().getSimpleName());
        }
    }

    /**
     * 筛出可供规划排序的结构提示，排除仅待验证的语义猜测。
     *
     * <p>100 条上限仅是当前传输保护，不表示客户全部 Schema 已被覆盖；
     * 未命中的意图仍应探索现场元数据或通过 Elicitation 澄清。</p>
     *
     * @return 不含业务行和凭证的提示列表
     */
    private List<Map<String, Object>> buildHints() {
        List<KnowledgeEntry> entries = knowledge.snapshot().stream()
                .filter(entry -> entry.source() != KnowledgeEntry.Source.PENDING)
                .sorted(Comparator.comparingDouble(KnowledgeEntry::confidence).reversed()
                        .thenComparing(Comparator.comparingLong(KnowledgeEntry::observedAt).reversed()))
                .limit(100).toList();
        List<Map<String, Object>> hints = new ArrayList<>();
        for (KnowledgeEntry entry : entries) {
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("layer", entry.layer().name());
            hint.put("key", entry.key());
            hint.put("value", entry.value());
            hint.put("source", entry.source().name());
            hint.put("confidence", entry.confidence());
            hint.put("expiresAt", entry.expiresAt());
            hints.add(hint);
        }
        return List.copyOf(hints);
    }
}
