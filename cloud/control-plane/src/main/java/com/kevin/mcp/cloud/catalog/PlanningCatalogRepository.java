package com.kevin.mcp.cloud.catalog;

import java.util.Optional;

/** 定义完整能力 Catalog 与 Runtime 注册的持久化边界。
 *
 * @author Kevin
 * @date 2026-09-11
 */
public interface PlanningCatalogRepository {

    /** 保存不可变的能力 Schema 快照。 */
    PlanningCatalog createCatalog(PlanningCatalog catalog);

    /** 按租户和实际 JAR 摘要读取能力 Schema。 */
    Optional<PlanningCatalog> findCatalog(String tenantId, String artifactDigest);

    /** 保存 Runtime 当前实例状态，并刷新其能力 Schema 快照。 */
    RuntimeRegistration saveRegistration(RuntimeRegistration registration);

    /** 查询指定 Runtime 是否已按当前 JAR 摘要注册。 */
    Optional<RuntimeRegistration> findRegistration(String tenantId, String instanceId, String artifactDigest);
}
