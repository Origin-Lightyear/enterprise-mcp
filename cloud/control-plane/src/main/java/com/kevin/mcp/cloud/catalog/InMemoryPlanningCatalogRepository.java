package com.kevin.mcp.cloud.catalog;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

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
        String key = catalog.tenantId() + ":" + catalog.artifactDigest();
        if (catalogs.putIfAbsent(key, catalog) != null) {
            throw new IllegalStateException("Artifact digest is already registered");
        }
        return catalog;
    }

    @Override
    public Optional<PlanningCatalog> findCatalog(String tenantId, String artifactDigest) {
        return Optional.ofNullable(catalogs.get(tenantId + ":" + artifactDigest));
    }

    @Override
    public RuntimeRegistration saveRegistration(RuntimeRegistration registration) {
        registrations.put(registration.tenantId() + ":" + registration.instanceId(), registration);
        return registration;
    }

    @Override
    public Optional<RuntimeRegistration> findRegistration(String tenantId, String instanceId, String artifactDigest) {
        return Optional.ofNullable(registrations.get(tenantId + ":" + instanceId))
                .filter(item -> artifactDigest.equals(item.artifactDigest()));
    }
}
