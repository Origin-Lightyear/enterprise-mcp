package com.kevin.mcp.cloud.catalog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存 Runtime 上报的完整能力 Schema，并按 JAR 摘要提供规划查询。
 *
 * @author Kevin
 * @date 2026-09-11
 */
@Service
@Profile("control-plane")
public class PlanningCatalogService {

    private final PlanningCatalogRepository repository;
    private final ConcurrentHashMap<String, PlanningCatalog> catalogCache = new ConcurrentHashMap<>();

    /** 使用内存仓储构造服务，供单元测试使用。 */
    public PlanningCatalogService() {
        this(new InMemoryPlanningCatalogRepository());
    }

    /** 注入持久化仓储。 */
    @Autowired
    public PlanningCatalogService(PlanningCatalogRepository repository) {
        this.repository = repository;
    }

    /** 保存 Runtime 注册和完整 Catalog；同一 JAR 摘要不能绑定不同 Schema。 */
    @Transactional
    public RuntimeRegistration registerRuntime(RuntimeRegistration registration) {
        if (registration == null || isBlank(registration.tenantId()) || isBlank(registration.instanceId())
                || isBlank(registration.artifactVersion()) || isBlank(registration.artifactDigest())
                || isBlank(registration.catalogJson())) {
            throw new IllegalArgumentException("Runtime identity, artifact and catalog fields are required");
        }
        CatalogCapabilityReader.read(registration.catalogJson());
        String catalogDigest = sha256(registration.catalogJson());
        Optional<PlanningCatalog> existing = repository.findCatalog(registration.tenantId(), registration.artifactDigest());
        if (existing.isEmpty()) {
            PlanningCatalog catalog = new PlanningCatalog(registration.tenantId(), registration.artifactVersion(),
                    registration.artifactDigest(), catalogDigest, registration.catalogJson(), Instant.now());
            repository.createCatalog(catalog);
            catalogCache.put(cacheKey(registration.tenantId(), registration.artifactDigest()), catalog);
        } else if (!existing.get().catalogDigest().equals(catalogDigest)) {
            throw new IllegalStateException("Artifact digest is already bound to a different catalog schema");
        } else {
            catalogCache.put(cacheKey(registration.tenantId(), registration.artifactDigest()), existing.get());
        }
        return repository.saveRegistration(registration);
    }

    /** 查询租户和 JAR 摘要对应的完整能力 Schema。 */
    public Optional<PlanningCatalog> find(String tenantId, String artifactDigest) {
        PlanningCatalog cached = catalogCache.get(cacheKey(tenantId, artifactDigest));
        return cached == null ? repository.findCatalog(tenantId, artifactDigest)
                .map(catalog -> {
                    catalogCache.put(cacheKey(tenantId, artifactDigest), catalog);
                    return catalog;
                }) : Optional.of(cached);
    }

    /** 查询已注册的 Runtime 实例。 */
    public Optional<RuntimeRegistration> findRegistration(String tenantId, String instanceId, String artifactDigest) {
        return repository.findRegistration(tenantId, instanceId, artifactDigest);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return "sha256:" + hex;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String cacheKey(String tenantId, String artifactDigest) {
        return tenantId + ":" + artifactDigest;
    }
}
