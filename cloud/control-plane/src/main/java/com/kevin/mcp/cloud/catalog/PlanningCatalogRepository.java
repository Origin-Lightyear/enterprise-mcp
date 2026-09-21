package com.kevin.mcp.cloud.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 定义不可变 Catalog Snapshot 与 Runtime 当前引用的持久化 seam。
 *
 * @author Kevin
 * @date 2026-09-11
 */
public interface PlanningCatalogRepository {

    /**
     * 保存不可变 Catalog Snapshot。
     *
     * @param catalog Catalog Snapshot
     * @return 已保存快照
     */
    PlanningCatalog createCatalog(PlanningCatalog catalog);

    /**
     * 按租户和精确 Catalog Digest 读取快照。
     *
     * @param tenantId 租户编号
     * @param catalogDigest Catalog Digest
     * @return 匹配快照
     */
    Optional<PlanningCatalog> findCatalog(String tenantId, String catalogDigest);

    /**
     * 保存 Runtime 当前实例引用。
     *
     * @param registration 当前实际引用
     * @return 已保存引用
     */
    RuntimeRegistration saveRegistration(RuntimeRegistration registration);

    /**
     * 查询指定 Runtime 是否绑定到精确制品和 Catalog。
     *
     * @param tenantId 租户编号
     * @param instanceId Runtime 实例编号
     * @param artifactDigest 制品摘要
     * @param catalogDigest Catalog Digest
     * @return 匹配引用
     */
    Optional<RuntimeRegistration> findRegistration(String tenantId, String instanceId, String artifactDigest,
                                                   String catalogDigest);

    /**
     * 删除超过宽限期且没有 Runtime 当前引用的 Catalog Snapshot。
     *
     * <p>实现必须在删除时再次检查引用，避免与 Runtime 引用更新并发时误删。</p>
     *
     * @param cutoff 创建时间上限
     * @return 实际删除的 Catalog Snapshot
     */
    List<PlanningCatalog> deleteUnreferencedBefore(Instant cutoff);
}
