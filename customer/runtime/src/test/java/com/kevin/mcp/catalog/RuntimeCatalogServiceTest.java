package com.kevin.mcp.catalog;

import com.kevin.mcp.agent.client.PlanningGatewayClient;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.agent.configuration.RuntimeArtifactDigest;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.execution.SemanticModelSnapshot;
import com.kevin.mcp.processor.JsonExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 Runtime Catalog 深模块在 MDL Update 和计划绑定上的完整行为。
 *
 * @author Kevin
 * @date 2026-09-18
 */
class RuntimeCatalogServiceTest {

    private final PrivateMcpToolSchemaRegistry schemaRegistry = mock(PrivateMcpToolSchemaRegistry.class);
    private final CurrentMdlAdapter mdlAdapter = mock(CurrentMdlAdapter.class);
    private final PlanningGatewayClient gatewayClient = mock(PlanningGatewayClient.class);
    private RuntimeCatalog runtimeCatalog;

    /** 构造固定 Runtime 身份及可控 adapters。 */
    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentMdlAdapter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mdlAdapter);
        when(schemaRegistry.getCloudPlanningSchemas()).thenReturn(List.of(
                capability("database.semantic-query", "WREN"),
                capability("erp.order-query", "JAVA")
        ));
        RuntimeIdentityProperties identity = new RuntimeIdentityProperties();
        identity.setTenantId("tenant-1");
        identity.setInstanceId("instance-1");
        identity.setArtifactVersion("1.0.0");
        identity.setArtifactDigest("sha256:artifact");
        RuntimeArtifactDigest artifactDigest = mock(RuntimeArtifactDigest.class);
        when(artifactDigest.getValue()).thenReturn("sha256:" + "a".repeat(64));
        runtimeCatalog = new RuntimeCatalogService(schemaRegistry, provider, identity, gatewayClient, artifactDigest);
    }

    /** 验证 Control Plane 未确认候选时不替换 Current MDL。 */
    @Test
    void shouldPreserveCurrentMdlWhenCloudConfirmationFails() {
        SemanticModelSnapshot current = mdl("old-mdl", "sales");
        SemanticModelSnapshot candidate = mdl("new-mdl", "sales");
        when(mdlAdapter.current()).thenReturn(Optional.of(current));
        when(mdlAdapter.prepare("sales")).thenReturn(new PreparedMdl("candidate-1", candidate));
        when(gatewayClient.confirmCatalog(anyMap())).thenThrow(new IllegalStateException("offline"));

        assertThatThrownBy(() -> runtimeCatalog.applyMdl(command("old-mdl", "operation-1")))
                .isInstanceOf(CatalogException.class)
                .extracting(exception -> ((CatalogException) exception).code())
                .isEqualTo(CatalogException.Code.CONTROL_PLANE_UNAVAILABLE);

        verify(mdlAdapter, never()).commit("candidate-1", "old-mdl");
        verify(mdlAdapter).discard("candidate-1");
    }

    /** 验证应用新 MDL 后绑定变化，旧计划立即失败关闭。 */
    @Test
    void shouldChangeBindingAndRejectOldPlanAfterSuccessfulUpdate() {
        SemanticModelSnapshot current = mdl("old-mdl", "sales");
        SemanticModelSnapshot candidate = mdl("new-mdl", "sales");
        when(mdlAdapter.current()).thenReturn(Optional.of(current));
        when(mdlAdapter.prepare("sales")).thenReturn(new PreparedMdl("candidate-1", candidate));
        when(mdlAdapter.commit("candidate-1", "old-mdl")).thenReturn(candidate);
        confirmAndReferenceSuccessfully();

        MdlUpdateResult result = runtimeCatalog.applyMdl(command("old-mdl", "operation-1"));

        assertThat(result.changed()).isTrue();
        assertThat(result.binding().mdlDigest()).isEqualTo("new-mdl");
        assertThat(runtimeCatalog.current().readiness()).isEqualTo(PlanningReadinessStatus.READY);
        assertThatThrownBy(() -> runtimeCatalog.requireExecutable(plan(result.binding(), "old-mdl")))
                .isInstanceOf(CatalogException.class)
                .extracting(exception -> ((CatalogException) exception).code())
                .isEqualTo(CatalogException.Code.STALE_PLAN);
        runtimeCatalog.requireExecutable(plan(result.binding(), "new-mdl"));
    }

    /** 验证成功请求可用原幂等键重放，不会再次提交 Current MDL。 */
    @Test
    void shouldReplaySuccessfulUpdateByIdempotencyKey() {
        SemanticModelSnapshot current = mdl("old-mdl", "sales");
        SemanticModelSnapshot candidate = mdl("new-mdl", "sales");
        when(mdlAdapter.current()).thenReturn(Optional.of(current), Optional.of(candidate));
        when(mdlAdapter.prepare("sales")).thenReturn(
                new PreparedMdl("candidate-1", candidate),
                new PreparedMdl("candidate-2", candidate));
        when(mdlAdapter.commit("candidate-1", "old-mdl")).thenReturn(candidate);
        confirmAndReferenceSuccessfully();

        MdlUpdateResult first = runtimeCatalog.applyMdl(command("old-mdl", "operation-1"));
        MdlUpdateResult replay = runtimeCatalog.applyMdl(command("old-mdl", "operation-1"));

        assertThat(replay).isEqualTo(first);
        verify(mdlAdapter, times(1)).commit("candidate-1", "old-mdl");
        verify(mdlAdapter).discard("candidate-2");
    }

    /** 验证没有 Current MDL 时 Catalog 不宣告 Wren 查询能力。 */
    @Test
    void shouldExcludeWrenCapabilityWithoutCurrentMdl() {
        when(mdlAdapter.current()).thenReturn(Optional.empty());
        confirmAndReferenceSuccessfully();

        runtimeCatalog.synchronize();

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) org.mockito.Mockito.mockingDetails(gatewayClient)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("confirmCatalog"))
                .findFirst().orElseThrow().getArgument(0);
        String catalogJson = String.valueOf(request.get("catalogJson"));
        assertThat(catalogJson).contains("erp.order-query").doesNotContain("database.semantic-query");
        assertThat(runtimeCatalog.requirePlanningBinding().mdlDigest()).isEmpty();
        verify(mdlAdapter, never()).datasourceDisplayNames();
    }

    /** 验证 Planning Catalog 发布中文显示名称，但语义模型仍保留稳定数据源键。 */
    @Test
    void shouldPublishDatasourceDisplayNamesWithoutChangingSourceKeys() {
        when(mdlAdapter.current()).thenReturn(Optional.of(mdl("current-mdl", "sales")));
        when(mdlAdapter.datasourceDisplayNames()).thenReturn(Map.of("sales", "销售数据库"));
        confirmAndReferenceSuccessfully();

        runtimeCatalog.synchronize();

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) org.mockito.Mockito.mockingDetails(gatewayClient)
                .getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("confirmCatalog"))
                .findFirst().orElseThrow().getArgument(0);
        String catalogJson = String.valueOf(request.get("catalogJson"));
        assertThat(catalogJson)
                .contains("\"semanticSourceDisplayNames\":{\"sales\":\"销售数据库\"}")
                .contains("\"sources\":{\"sales\":")
                .doesNotContain("\"sources\":{\"销售数据库\":");
    }

    /** 验证显示元数据不可用时清除可执行绑定并等待自动恢复。 */
    @Test
    void shouldFailClosedWhenDatasourceDisplayNamesAreUnavailable() {
        when(mdlAdapter.current()).thenReturn(Optional.of(mdl("current-mdl", "sales")));
        when(mdlAdapter.datasourceDisplayNames()).thenReturn(Map.of("sales", "销售数据库"));
        confirmAndReferenceSuccessfully();
        runtimeCatalog.synchronize();
        when(mdlAdapter.datasourceDisplayNames()).thenThrow(new IllegalStateException("adapter offline"));

        assertThatThrownBy(() -> runtimeCatalog.synchronize())
                .isInstanceOf(CatalogException.class)
                .extracting(exception -> ((CatalogException) exception).code())
                .isEqualTo(CatalogException.Code.NOT_READY);

        assertThat(runtimeCatalog.current().readiness()).isEqualTo(PlanningReadinessStatus.NOT_READY);
        assertThatThrownBy(() -> runtimeCatalog.requirePlanningBinding())
                .isInstanceOf(CatalogException.class);
    }

    private void confirmAndReferenceSuccessfully() {
        when(gatewayClient.confirmCatalog(anyMap())).thenAnswer(invocation -> {
            Map<?, ?> request = invocation.getArgument(0);
            return Map.of("catalogDigest", request.get("catalogDigest"));
        });
        when(gatewayClient.referenceCatalog(anyMap())).thenAnswer(invocation -> Map.of("saved", true));
    }

    /** 验证删除最后一个生效源前先确认不含 Wren 能力的 Catalog。 */
    @Test
    void shouldConfirmEmptyCatalogBeforeDeletingLastDatasource() {
        when(schemaRegistry.getCloudPlanningSchemas()).thenReturn(List.of(capability("database.semantic-query", "WREN")));
        when(mdlAdapter.current()).thenReturn(Optional.of(mdl("old-mdl", "sales")));
        when(mdlAdapter.prepareDeletion("sales")).thenReturn(new PreparedMdl("delete-1", SemanticModelSnapshot.empty()));
        when(mdlAdapter.commit("delete-1", "old-mdl")).thenReturn(SemanticModelSnapshot.empty());
        confirmAndReferenceSuccessfully();

        MdlUpdateResult result = runtimeCatalog.applyMdl(new MdlUpdateCommand("sales", "old-mdl", "delete-1", "admin-1", true));

        assertThat(result.binding().mdlDigest()).isEmpty();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(gatewayClient, mdlAdapter);
        order.verify(gatewayClient).confirmCatalog(org.mockito.ArgumentMatchers.argThat(request ->
                "{\"capabilities\":[]}".equals(request.get("catalogJson"))));
        order.verify(mdlAdapter).commit("delete-1", "old-mdl");
        order.verify(gatewayClient).referenceCatalog(anyMap());
        assertThatThrownBy(() -> runtimeCatalog.requireExecutable(plan(result.binding(), "old-mdl")))
                .isInstanceOf(CatalogException.class);
    }

    /** 验证删除候选未被云端确认时不删除连接与 Current MDL。 */
    @Test
    void shouldPreserveDatasourceWhenDeletionConfirmationFails() {
        when(mdlAdapter.current()).thenReturn(Optional.of(mdl("old-mdl", "sales")));
        when(mdlAdapter.prepareDeletion("sales")).thenReturn(new PreparedMdl("delete-1", SemanticModelSnapshot.empty()));
        when(gatewayClient.confirmCatalog(anyMap())).thenThrow(new IllegalStateException("offline"));

        assertThatThrownBy(() -> runtimeCatalog.applyMdl(new MdlUpdateCommand("sales", "old-mdl", "delete-1", "admin-1", true)))
                .isInstanceOf(CatalogException.class);

        verify(mdlAdapter, never()).commit("delete-1", "old-mdl");
        verify(mdlAdapter).discard("delete-1");
    }

    /** 验证云端拒绝删除候选时，保留本地数据并向管理页面返回明确原因。 */
    @Test
    void shouldExposeCloudRejectionWithoutDeletingDatasource() {
        when(mdlAdapter.current()).thenReturn(Optional.of(mdl("old-mdl", "sales")));
        when(mdlAdapter.prepareDeletion("sales")).thenReturn(new PreparedMdl("delete-1", SemanticModelSnapshot.empty()));
        when(gatewayClient.confirmCatalog(anyMap())).thenThrow(new PlanningGatewayClient.CatalogRequestException(
                400, "Control Plane rejected Catalog request: HTTP 400 [INVALID_REQUEST]: invalid catalog", null));

        assertThatThrownBy(() -> runtimeCatalog.applyMdl(
                new MdlUpdateCommand("sales", "old-mdl", "delete-1", "admin-1", true)))
                .isInstanceOf(CatalogException.class).hasMessageContaining("HTTP 400 [INVALID_REQUEST]: invalid catalog")
                .satisfies(exception -> assertThat(((CatalogException) exception).code())
                        .isEqualTo(CatalogException.Code.VALIDATION_FAILED));

        verify(mdlAdapter, never()).commit("delete-1", "old-mdl");
        verify(mdlAdapter).discard("delete-1");
    }

    /** 验证未生效源也提交删除，并可用同一幂等键重试。 */
    @Test
    void shouldCommitDeletionEvenWhenMdlDigestIsUnchanged() {
        SemanticModelSnapshot current = mdl("old-mdl", "finance");
        when(mdlAdapter.current()).thenReturn(Optional.of(current));
        when(mdlAdapter.prepareDeletion("sales")).thenReturn(new PreparedMdl("delete-1", current));
        when(mdlAdapter.commit("delete-1", "old-mdl")).thenReturn(current);
        confirmAndReferenceSuccessfully();
        MdlUpdateCommand command = new MdlUpdateCommand("sales", "old-mdl", "delete-1", "admin-1", true);

        MdlUpdateResult result = runtimeCatalog.applyMdl(command);
        assertThat(runtimeCatalog.applyMdl(command)).isEqualTo(result);

        verify(mdlAdapter, times(1)).commit("delete-1", "old-mdl");
    }

    private PrivateMcpToolSchemaRegistry.CloudPlanningSchema capability(String id, String executorType) {
        return new PrivateMcpToolSchemaRegistry.CloudPlanningSchema(
                id, 1, id, null, null, executorType, "SINGLE", "READ_ONLY", "test");
    }

    private SemanticModelSnapshot mdl(String digest, String sourceKey) {
        return new SemanticModelSnapshot(digest,
                Map.of("sources", Map.of(sourceKey, Map.of("models", List.of()))));
    }

    private MdlUpdateCommand command(String expectedDigest, String idempotencyKey) {
        return new MdlUpdateCommand("sales", expectedDigest, idempotencyKey, "admin-1");
    }

    private JsonExecutionPlan plan(PlanningBinding binding, String mdlDigest) {
        return new JsonExecutionPlan("plan-1", 1, binding.tenantId(), 1001L, binding.instanceId(),
                binding.artifactVersion(), binding.artifactDigest(), binding.catalogDigest(), mdlDigest,
                "2026-09-18T00:00:00Z", "BEST_EFFORT_CURRENT", "query", List.of(), null);
    }
}
