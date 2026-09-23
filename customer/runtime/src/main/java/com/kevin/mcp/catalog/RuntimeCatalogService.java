package com.kevin.mcp.catalog;

import com.kevin.mcp.agent.client.PlanningGatewayClient;
import com.kevin.mcp.agent.configuration.RuntimeIdentityProperties;
import com.kevin.mcp.agent.configuration.RuntimeArtifactDigest;
import com.kevin.mcp.catalog.CatalogException.Code;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.execution.SemanticModelSnapshot;
import com.kevin.mcp.processor.JsonExecutionPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实现 Runtime Catalog deep module，集中维护摘要、云端确认、Current MDL 提交和计划失效。
 *
 * @author Kevin
 * @date 2026-09-18
 */
@Service
public class RuntimeCatalogService implements RuntimeCatalog {

    private static final Logger log = LoggerFactory.getLogger(RuntimeCatalogService.class);
    private static final String WREN_CAPABILITY_ID = "database.semantic-query";
    private static final int MAX_IDEMPOTENCY_RECORDS = 256;

    private final Object updateLock = new Object();
    private final PrivateMcpToolSchemaRegistry schemaRegistry;
    private final ObjectProvider<CurrentMdlAdapter> currentMdlAdapterProvider;
    private final RuntimeIdentityProperties identityProperties;
    private final RuntimeArtifactDigest artifactDigest;
    private final PlanningGatewayClient gatewayClient;
    private final AtomicReference<CurrentCatalogSnapshot> currentSnapshot = new AtomicReference<>();
    private final AtomicReference<CatalogState> state = new AtomicReference<>(new CatalogState(
            PlanningReadinessStatus.NOT_READY, "", "", "STARTING", Instant.now()));
    private final LinkedHashMap<String, OperationRecord> operations = new LinkedHashMap<>();
    private boolean restorePending;

    /**
     * 注入 Catalog 构建、Wren 和 Control Plane adapters。
     *
     * @param schemaRegistry 本地固定能力注册表
     * @param currentMdlAdapterProvider 可选 Wren Current MDL adapter
     * @param identityProperties Runtime 身份
     * @param gatewayClient Control Plane HTTP adapter
     */
    public RuntimeCatalogService(PrivateMcpToolSchemaRegistry schemaRegistry,
                                 ObjectProvider<CurrentMdlAdapter> currentMdlAdapterProvider,
                                 RuntimeIdentityProperties identityProperties,
                                 PlanningGatewayClient gatewayClient,
                                 RuntimeArtifactDigest artifactDigest) {
        this.schemaRegistry = schemaRegistry;
        this.currentMdlAdapterProvider = currentMdlAdapterProvider;
        this.identityProperties = identityProperties;
        this.gatewayClient = gatewayClient;
        this.artifactDigest = artifactDigest;
    }

    /**
     * 在 Runtime 启动完成后重建并确认本地实际 Current Catalog。
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize(ApplicationReadyEvent event) {
        try {
            synchronize();
        } catch (CatalogException exception) {
            log.error("Runtime Catalog initialization failed; planning remains NOT_READY: tenantId={}, instanceId={}, "
                            + "artifactVersion={}, artifactDigest={}, code={}, reason={}",
                    identityProperties.getTenantId(), identityProperties.getInstanceId(),
                    identityProperties.getArtifactVersion(), artifactDigest.getValue(),
                    exception.code(), exception.getMessage());
        }
    }

    /**
     * 在启动确认失败或引用待恢复时自动重试，不要求管理员手工同步 Catalog。
     */
    @Scheduled(fixedDelayString = "${enterprise.catalog.recovery-delay:30s}")
    public void recoverWhenNotReady() {
        if (state.get().readiness() == PlanningReadinessStatus.READY) {
            return;
        }
        try {
            synchronize();
        } catch (CatalogException exception) {
            log.warn("Runtime Catalog recovery is still pending: instanceId={}, code={}",
                    identityProperties.getInstanceId(), exception.code());
        }
    }

    @Override
    public MdlUpdateResult applyMdl(MdlUpdateCommand command) {
        validateCommand(command);
        CurrentMdlAdapter adapter = currentMdlAdapterProvider.getIfAvailable();
        if (adapter == null) {
            throw new CatalogException(Code.VALIDATION_FAILED, "Wren Current MDL adapter is unavailable");
        }
        synchronized (updateLock) {
            if (restorePending) {
                throw new CatalogException(Code.NOT_READY, "Wren backup restore is in progress");
            }
            Optional<SemanticModelSnapshot> currentMdl = adapter.current();
            String actualMdlDigest = currentMdl.map(SemanticModelSnapshot::digest).orElse("");
            PreparedMdl prepared;
            try {
                prepared = adapter.prepare(command.profileName());
            } catch (RuntimeException exception) {
                throw new CatalogException(Code.VALIDATION_FAILED, "Candidate MDL validation failed", exception);
            }
            String operationDigest = prepared.snapshot().digest();
            OperationRecord existing = operations.get(command.idempotencyKey());
            if (existing != null) {
                adapter.discard(prepared.candidateToken());
                if (!existing.mdlDigest().equals(operationDigest)) {
                    throw new CatalogException(Code.IDEMPOTENCY_CONFLICT,
                            "Idempotency key is already bound to different MDL content");
                }
                return existing.result();
            }
            if (!normalized(command.expectedMdlDigest()).equals(actualMdlDigest)) {
                adapter.discard(prepared.candidateToken());
                throw new CatalogException(Code.CONCURRENT_UPDATE, "Current MDL changed while it was being edited");
            }

            boolean committed = false;
            try {
                CurrentCatalogSnapshot candidate = buildSnapshot(Optional.of(prepared.snapshot()));
                confirm(candidate);
                if (actualMdlDigest.equals(prepared.snapshot().digest())) {
                    reference(candidate);
                    publishReady(candidate);
                    MdlUpdateResult result = result(candidate, false);
                    remember(command.idempotencyKey(), operationDigest, result);
                    return result;
                }

                SemanticModelSnapshot committedMdl = adapter.commit(
                        prepared.candidateToken(), command.expectedMdlDigest());
                committed = true;
                if (!prepared.snapshot().digest().equals(committedMdl.digest())) {
                    markNotReady(candidate, "DIGEST_MISMATCH");
                    throw new CatalogException(Code.DIGEST_MISMATCH,
                            "Committed Current MDL differs from the confirmed candidate");
                }
                try {
                    reference(candidate);
                } catch (CatalogException exception) {
                    markNotReady(candidate, "REFERENCE_FINALIZATION_PENDING");
                    throw new CatalogException(Code.REFERENCE_FINALIZATION_PENDING,
                            "Current MDL was applied, but its cloud reference still requires recovery", exception);
                }
                publishReady(candidate);
                MdlUpdateResult result = result(candidate, true);
                remember(command.idempotencyKey(), operationDigest, result);
                log.info("Current MDL applied: tenantId={}, instanceId={}, operatorId={}, idempotencyKey={}, "
                                + "mdlDigest={}, catalogDigest={}",
                        identityProperties.getTenantId(), identityProperties.getInstanceId(), command.operatorId(),
                        command.idempotencyKey(), candidate.mdlDigest(), candidate.catalogDigest());
                return result;
            } finally {
                if (!committed) {
                    adapter.discard(prepared.candidateToken());
                }
            }
        }
    }

    @Override
    public CatalogState current() {
        return state.get();
    }

    @Override
    public PlanningBinding requirePlanningBinding() {
        CatalogState currentState = state.get();
        CurrentCatalogSnapshot snapshot = currentSnapshot.get();
        if (currentState.readiness() != PlanningReadinessStatus.READY || snapshot == null) {
            throw new CatalogException(Code.NOT_READY, "Runtime Catalog is not ready for planning");
        }
        return binding(snapshot);
    }

    @Override
    public void requireExecutable(JsonExecutionPlan plan) {
        PlanningBinding binding = requirePlanningBinding();
        if (plan == null
                || !binding.tenantId().equals(plan.tenantId())
                || !binding.instanceId().equals(plan.instanceId())
                || !binding.artifactVersion().equals(plan.runtimeVersion())
                || !binding.artifactDigest().equals(plan.artifactDigest())
                || !binding.catalogDigest().equals(plan.catalogDigest())
                || !binding.mdlDigest().equals(normalized(plan.mdlDigest()))) {
            throw new CatalogException(Code.STALE_PLAN,
                    "Execution plan is not bound to the Runtime Current Catalog");
        }
    }

    @Override
    public void synchronize() {
        synchronized (updateLock) {
            if (restorePending) {
                throw new CatalogException(Code.NOT_READY, "Wren backup restore is in progress");
            }
            Optional<SemanticModelSnapshot> currentMdl = Optional.empty();
            CurrentMdlAdapter adapter = currentMdlAdapterProvider.getIfAvailable();
            if (adapter != null) {
                currentMdl = adapter.current();
            }
            CurrentCatalogSnapshot snapshot = buildSnapshot(currentMdl);
            try {
                confirm(snapshot);
                reference(snapshot);
                publishReady(snapshot);
                log.info("Runtime Catalog synchronized: tenantId={}, instanceId={}, artifactVersion={}, "
                                + "artifactDigest={}, catalogDigest={}, mdlDigest={}",
                        identityProperties.getTenantId(), identityProperties.getInstanceId(),
                        identityProperties.getArtifactVersion(), artifactDigest.getValue(),
                        snapshot.catalogDigest(), snapshot.mdlDigest());
            } catch (CatalogException exception) {
                markNotReady(snapshot, exception.code().name());
                throw exception;
            }
        }
    }

    @Override
    public void invalidate(String reasonCode) {
        synchronized (updateLock) {
            restorePending = true;
            CatalogState previous = state.get();
            currentSnapshot.set(null);
            state.set(new CatalogState(PlanningReadinessStatus.NOT_READY,
                    previous.catalogDigest(), previous.mdlDigest(), reasonCode, Instant.now()));
        }
    }

    @Override
    public void resumeAfterRestore() {
        synchronized (updateLock) {
            restorePending = false;
            synchronize();
        }
    }

    private CurrentCatalogSnapshot buildSnapshot(Optional<SemanticModelSnapshot> currentMdl) {
        List<PrivateMcpToolSchemaRegistry.CloudPlanningSchema> capabilities = new ArrayList<>();
        for (PrivateMcpToolSchemaRegistry.CloudPlanningSchema capability : schemaRegistry.getCloudPlanningSchemas()) {
            if (currentMdl.isPresent() || !WREN_CAPABILITY_ID.equals(capability.capabilityId())) {
                capabilities.add(capability);
            }
        }
        capabilities.sort(Comparator.comparing(PrivateMcpToolSchemaRegistry.CloudPlanningSchema::capabilityId));
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("capabilities", List.copyOf(capabilities));
        String mdlDigest = "";
        if (currentMdl.isPresent()) {
            SemanticModelSnapshot mdl = currentMdl.get();
            if (mdl.digest() == null || mdl.digest().isBlank() || mdl.manifest().isEmpty()) {
                throw new CatalogException(Code.VALIDATION_FAILED, "Current MDL snapshot is incomplete");
            }
            mdlDigest = mdl.digest();
            catalog.put("mdlDigest", mdl.digest());
            catalog.put("semanticModel", mdl.manifest());
        }
        String catalogJson = CatalogSnapshotCodec.encode(catalog);
        return new CurrentCatalogSnapshot(catalogJson, CatalogSnapshotCodec.digest(catalogJson), mdlDigest);
    }

    private void confirm(CurrentCatalogSnapshot snapshot) {
        Map<String, Object> request = identityPayload();
        request.put("catalogDigest", snapshot.catalogDigest());
        request.put("catalogJson", snapshot.catalogJson());
        Map<?, ?> confirmation;
        try {
            confirmation = gatewayClient.confirmCatalog(request);
        } catch (RuntimeException exception) {
            throw new CatalogException(Code.CONTROL_PLANE_UNAVAILABLE,
                    "Control Plane did not confirm the Catalog Snapshot", exception);
        }
        if (!snapshot.catalogDigest().equals(String.valueOf(confirmation.get("catalogDigest")))) {
            throw new CatalogException(Code.DIGEST_MISMATCH,
                    "Control Plane confirmed a different Catalog Digest");
        }
    }

    private void reference(CurrentCatalogSnapshot snapshot) {
        Map<String, Object> request = identityPayload();
        request.put("catalogDigest", snapshot.catalogDigest());
        try {
            gatewayClient.referenceCatalog(request);
        } catch (RuntimeException exception) {
            throw new CatalogException(Code.CONTROL_PLANE_UNAVAILABLE,
                    "Control Plane did not save the Runtime Catalog reference", exception);
        }
    }

    private Map<String, Object> identityPayload() {
        if (isBlank(identityProperties.getTenantId()) || isBlank(identityProperties.getInstanceId())
                || isBlank(identityProperties.getArtifactVersion()) || isBlank(artifactDigest.getValue())) {
            throw new CatalogException(Code.NOT_READY, "Runtime identity and artifact fields are required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", identityProperties.getTenantId());
        payload.put("instanceId", identityProperties.getInstanceId());
        payload.put("artifactVersion", identityProperties.getArtifactVersion());
        payload.put("artifactDigest", artifactDigest.getValue());
        return payload;
    }

    private void publishReady(CurrentCatalogSnapshot snapshot) {
        currentSnapshot.set(snapshot);
        state.set(new CatalogState(PlanningReadinessStatus.READY, snapshot.catalogDigest(), snapshot.mdlDigest(),
                "", Instant.now()));
    }

    private void markNotReady(CurrentCatalogSnapshot snapshot, String reasonCode) {
        currentSnapshot.set(snapshot);
        state.set(new CatalogState(PlanningReadinessStatus.NOT_READY, snapshot.catalogDigest(), snapshot.mdlDigest(),
                reasonCode, Instant.now()));
    }

    private PlanningBinding binding(CurrentCatalogSnapshot snapshot) {
        return new PlanningBinding(identityProperties.getTenantId(), identityProperties.getInstanceId(),
                identityProperties.getArtifactVersion(), artifactDigest.getValue(),
                snapshot.catalogDigest(), snapshot.mdlDigest());
    }

    private MdlUpdateResult result(CurrentCatalogSnapshot snapshot, boolean changed) {
        return new MdlUpdateResult(binding(snapshot), changed, Instant.now());
    }

    private void remember(String idempotencyKey, String mdlDigest, MdlUpdateResult result) {
        operations.put(idempotencyKey, new OperationRecord(mdlDigest, result));
        while (operations.size() > MAX_IDEMPOTENCY_RECORDS) {
            String oldestKey = operations.keySet().iterator().next();
            operations.remove(oldestKey);
        }
    }

    private void validateCommand(MdlUpdateCommand command) {
        if (command == null || isBlank(command.profileName()) || isBlank(command.idempotencyKey())
                || isBlank(command.operatorId())) {
            throw new CatalogException(Code.VALIDATION_FAILED,
                    "Profile, idempotency key and authenticated operator are required");
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record CurrentCatalogSnapshot(String catalogJson, String catalogDigest, String mdlDigest) {
    }

    private record OperationRecord(String mdlDigest, MdlUpdateResult result) {
    }
}
