package com.kevin.mcp.cloud.catalog;

import com.kevin.mcp.catalog.CatalogSnapshotCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证云端 Catalog Registry 只接受复算一致且可精确寻址的快照。
 *
 * @author Kevin
 * @date 2026-09-18
 */
class PlanningCatalogServiceTest {

    private final InMemoryPlanningCatalogRepository repository = new InMemoryPlanningCatalogRepository();
    private final PlanningCatalogService service = new PlanningCatalogService(repository);

    /** 验证 Runtime 声明的摘要不匹配 canonical Catalog 时拒绝确认。 */
    @Test
    void shouldRejectDeclaredDigestMismatch() {
        String catalogJson = catalog("erp.order-query");
        CatalogCandidate candidate = candidate("sha256:invalid", catalogJson);

        assertThatThrownBy(() -> service.confirm(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }

    /** 验证同一制品可以确认多个内容不同的 Catalog Snapshot。 */
    @Test
    void shouldKeepMultipleCatalogSnapshotsForSameArtifact() {
        String firstJson = catalog("erp.order-query");
        String secondJson = catalog("erp.customer-query");
        String firstDigest = CatalogSnapshotCodec.digest(firstJson);
        String secondDigest = CatalogSnapshotCodec.digest(secondJson);

        service.confirm(candidate(firstDigest, firstJson));
        service.confirm(candidate(secondDigest, secondJson));

        assertThat(service.find("tenant-1", firstDigest)).isPresent();
        assertThat(service.find("tenant-1", secondDigest)).isPresent();
        assertThat(firstDigest).isNotEqualTo(secondDigest);
    }

    /** 验证清理只删除超过宽限期且没有 Runtime 当前引用的快照。 */
    @Test
    void shouldCleanupOnlyOldUnreferencedCatalogs() {
        String staleJson = catalog("erp.stale-query");
        String currentJson = catalog("erp.current-query");
        String staleDigest = CatalogSnapshotCodec.digest(staleJson);
        String currentDigest = CatalogSnapshotCodec.digest(currentJson);
        Instant old = Instant.now().minusSeconds(3600);
        repository.createCatalog(new PlanningCatalog("tenant-1", staleDigest, staleJson, old));
        repository.createCatalog(new PlanningCatalog("tenant-1", currentDigest, currentJson, old));
        repository.saveRegistration(new RuntimeRegistration(
                "tenant-1", "instance-1", "1.0.0", "sha256:artifact", currentDigest));
        assertThat(service.find("tenant-1", staleDigest)).isPresent();
        assertThat(service.find("tenant-1", currentDigest)).isPresent();

        assertThat(service.cleanupUnreferenced()).isEqualTo(1);

        assertThat(service.find("tenant-1", staleDigest)).isEmpty();
        assertThat(service.find("tenant-1", currentDigest)).isPresent();
    }

    private CatalogCandidate candidate(String digest, String json) {
        return new CatalogCandidate("tenant-1", "instance-1", "1.0.0", "sha256:artifact", digest, json);
    }

    private String catalog(String capabilityId) {
        return "{\"capabilities\":[{\"capabilityId\":\"" + capabilityId
                + "\",\"capabilityVersion\":1,\"parameters\":{\"type\":\"object\"}}]}";
    }
}
