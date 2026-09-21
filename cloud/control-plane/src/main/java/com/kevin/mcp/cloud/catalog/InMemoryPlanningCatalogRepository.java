package com.kevin.mcp.cloud.catalog;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 提供单元测试使用的内存目录仓储，不参与 Control Plane 运行时装配。
 *
 * @author Kevin
 * @date 2026-09-11
 */
@Repository
@Profile("test")
public class InMemoryPlanningCatalogRepository implements PlanningCatalogRepository {
    private final ConcurrentHashMap<String, PlanningCatalog> catalogs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RuntimeRegistration> registrations = new ConcurrentHashMap<>();

    @Override
    public PlanningCatalog createCatalog(PlanningCatalog catalog) {
        String key = catalog.tenantId() + ":" + catalog.catalogDigest();
        if (catalogs.putIfAbsent(key, catalog) != null) {
            throw new IllegalStateException("Catalog digest is already registered");
        }
        return catalog;
    }

    @Override
    public Optional<PlanningCatalog> findCatalog(String tenantId, String catalogDigest) {
        return Optional.ofNullable(catalogs.get(tenantId + ":" + catalogDigest));
    }

    @Override
    public RuntimeRegistration saveRegistration(RuntimeRegistration registration) {
        registrations.put(registration.tenantId() + ":" + registration.instanceId(), registration);
        return registration;
    }

    @Override
    public Optional<RuntimeRegistration> findRegistration(String tenantId, String instanceId, String artifactDigest,
                                                          String catalogDigest) {
        return Optional.ofNullable(registrations.get(tenantId + ":" + instanceId))
                .filter(item -> artifactDigest.equals(item.artifactDigest()))
                .filter(item -> catalogDigest.equals(item.catalogDigest()));
    }

    @Override
    public List<PlanningCatalog> deleteUnreferencedBefore(Instant cutoff) {
        List<PlanningCatalog> deleted = new ArrayList<>();
        for (PlanningCatalog catalog : catalogs.values()) {
            boolean referenced = registrations.values().stream()
                    .anyMatch(item -> item.tenantId().equals(catalog.tenantId())
                            && item.catalogDigest().equals(catalog.catalogDigest()));
            String key = catalog.tenantId() + ":" + catalog.catalogDigest();
            if (!referenced && catalog.createdAt().isBefore(cutoff) && catalogs.remove(key, catalog)) {
                deleted.add(catalog);
            }
        }
        return List.copyOf(deleted);
    }
}
