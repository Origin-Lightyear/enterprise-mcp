package com.kevin.mcp.cloud.catalog;

import com.kevin.mcp.catalog.CatalogSnapshotCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 确认不可变 Catalog Snapshot，并维护 Runtime 当前实际引用。
 *
 * @author Kevin
 * @date 2026-09-11
 */
@Service
@Profile("control-plane")
public class PlanningCatalogService {

    private final PlanningCatalogRepository repository;
    private final ConcurrentHashMap<String, PlanningCatalog> catalogCache = new ConcurrentHashMap<>();

    @Value("${enterprise.control-plane.catalog-retention-grace:10m}")
    private Duration retentionGrace = Duration.ofMinutes(10);

    /**
     * 使用内存 adapter 构造模块，供接口级测试使用。
     */
    public PlanningCatalogService() {
        this(new InMemoryPlanningCatalogRepository());
    }

    /**
     * 注入持久化 adapter。
     *
     * @param repository Catalog 持久化 adapter
     */
    @Autowired
    public PlanningCatalogService(PlanningCatalogRepository repository) {
        this.repository = repository;
    }

    /**
     * 独立规范化、复算并确认不可变 Catalog Snapshot。
     *
     * @param candidate Runtime 上传的完整候选快照
     * @return Control Plane 确认结果
     * @throws IllegalArgumentException 字段缺失、Schema 无效或摘要不一致
     */
    @Transactional
    public CatalogConfirmation confirm(CatalogCandidate candidate) {
        if (candidate == null || isBlank(candidate.tenantId()) || isBlank(candidate.instanceId())
                || isBlank(candidate.artifactVersion()) || isBlank(candidate.artifactDigest())
                || isBlank(candidate.catalogDigest()) || isBlank(candidate.catalogJson())) {
            throw new IllegalArgumentException("Runtime identity, artifact and catalog fields are required");
        }
        String canonicalJson = CatalogSnapshotCodec.canonicalize(candidate.catalogJson());
        CatalogCapabilityReader.read(canonicalJson);
        String catalogDigest = CatalogSnapshotCodec.digest(canonicalJson);
        if (!catalogDigest.equals(candidate.catalogDigest())) {
            throw new IllegalArgumentException("Catalog digest does not match canonical Catalog JSON");
        }
        Optional<PlanningCatalog> existing = repository.findCatalog(candidate.tenantId(), catalogDigest);
        if (existing.isEmpty()) {
            PlanningCatalog catalog = new PlanningCatalog(candidate.tenantId(), catalogDigest, canonicalJson, Instant.now());
            repository.createCatalog(catalog);
            catalogCache.put(cacheKey(candidate.tenantId(), catalogDigest), catalog);
        } else if (!existing.get().catalogJson().equals(canonicalJson)) {
            throw new IllegalStateException("Catalog digest is already bound to different canonical content");
        } else {
            catalogCache.put(cacheKey(candidate.tenantId(), catalogDigest), existing.get());
        }
        return new CatalogConfirmation(catalogDigest, Instant.now());
    }

    /**
     * 更新 Runtime 已在本地提交的当前 Catalog 引用。
     *
     * @param reference Runtime 当前实际引用
     * @return 已保存引用
     * @throws IllegalArgumentException 字段缺失或 Catalog 尚未确认
     */
    @Transactional
    public RuntimeRegistration reference(RuntimeCatalogReference reference) {
        if (reference == null || isBlank(reference.tenantId()) || isBlank(reference.instanceId())
                || isBlank(reference.artifactVersion()) || isBlank(reference.artifactDigest())
                || isBlank(reference.catalogDigest())) {
            throw new IllegalArgumentException("Runtime identity, artifact and catalog reference fields are required");
        }
        if (find(reference.tenantId(), reference.catalogDigest()).isEmpty()) {
            throw new IllegalArgumentException("Catalog Snapshot must be confirmed before it is referenced");
        }
        return repository.saveRegistration(new RuntimeRegistration(reference.tenantId(), reference.instanceId(),
                reference.artifactVersion(), reference.artifactDigest(), reference.catalogDigest()));
    }

    /**
     * 按精确摘要查询租户 Catalog Snapshot。
     *
     * @param tenantId 租户编号
     * @param catalogDigest Catalog Digest
     * @return 匹配快照
     */
    public Optional<PlanningCatalog> find(String tenantId, String catalogDigest) {
        PlanningCatalog cached = catalogCache.get(cacheKey(tenantId, catalogDigest));
        return cached == null ? repository.findCatalog(tenantId, catalogDigest)
                .map(catalog -> {
                    catalogCache.put(cacheKey(tenantId, catalogDigest), catalog);
                    return catalog;
                }) : Optional.of(cached);
    }

    /**
     * 查询已绑定精确制品和 Catalog 的 Runtime 实例。
     *
     * @param tenantId 租户编号
     * @param instanceId Runtime 实例编号
     * @param artifactDigest 制品摘要
     * @param catalogDigest Catalog Digest
     * @return 匹配引用
     */
    public Optional<RuntimeRegistration> findRegistration(String tenantId, String instanceId, String artifactDigest,
                                                          String catalogDigest) {
        return repository.findRegistration(tenantId, instanceId, artifactDigest, catalogDigest);
    }

    /**
     * 清理超过宽限期且不再被 Runtime 引用的 Catalog Snapshot。
     *
     * @return 实际删除数量
     */
    @Transactional
    @Scheduled(fixedDelayString = "${enterprise.control-plane.catalog-cleanup-delay:1h}")
    public int cleanupUnreferenced() {
        List<PlanningCatalog> deleted = repository.deleteUnreferencedBefore(Instant.now().minus(retentionGrace));
        deleted.forEach(catalog -> catalogCache.remove(cacheKey(catalog.tenantId(), catalog.catalogDigest()), catalog));
        return deleted.size();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String cacheKey(String tenantId, String catalogDigest) {
        return tenantId + ":" + catalogDigest;
    }
}
