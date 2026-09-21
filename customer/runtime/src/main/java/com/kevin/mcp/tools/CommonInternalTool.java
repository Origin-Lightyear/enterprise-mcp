package com.kevin.mcp.tools;

import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.identity.AgentVerify;
import com.kevin.mcp.agent.identity.VerifiedEmployee;
import com.kevin.mcp.agent.model.EmployeeAuth;
import com.kevin.mcp.agent.model.EmployeeLlmInvocationConfig;
import com.kevin.mcp.annotation.ExecutorType;
import com.kevin.mcp.audit.ExecutionAuditService;
import com.kevin.mcp.audit.PermissionAuditOutboxService;
import com.kevin.mcp.catalog.RuntimeCatalog;
import com.kevin.mcp.authorization.LocalAuthorizationService;
import com.kevin.mcp.authorization.PermissionReviewResult;
import com.kevin.mcp.authorization.PermissionReviewService;
import com.kevin.mcp.authorization.PlanBindingValidator;
import com.kevin.mcp.capability.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.clarification.ClarificationElicitationService;
import com.kevin.mcp.clarification.ClarificationRequest;
import com.kevin.mcp.clarification.ClarificationRequiredException;
import com.kevin.mcp.clarification.ClarificationResolution;
import com.kevin.mcp.execution.ExecutionProgressListener;
import com.kevin.mcp.execution.ExecutionReceipt;
import com.kevin.mcp.execution.JsonPlanExecutor;
import com.kevin.mcp.execution.JsonPlanParser;
import com.kevin.mcp.execution.RequestExecutionContext;
import com.kevin.mcp.execution.RequestExecutionContextHolder;
import com.kevin.mcp.execution.SemanticModelSnapshot;
import com.kevin.mcp.execution.SemanticModelSnapshotProvider;
import com.kevin.mcp.planning.PlanningService;
import com.kevin.mcp.processor.JsonExecutionPlan;
import com.kevin.mcp.processor.JsonExecutionStep;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * 执行客户 MCP Tool 共用的认证、规划、授权、审计、执行和脱敏流程。
 *
 * <p>权限规则和员工模型配置只存在于客户 Runtime。Cloud Control Plane 只接收规划所需身份与意图，
 * Wren 通过请求级上下文获得员工模型配置，避免密钥进入计划和 Catalog。</p>
 *
 * @author Kevin
 * @date 2026-09-17
 */
@Component
public class CommonInternalTool {

    private static final Logger log = LoggerFactory.getLogger(CommonInternalTool.class);
    private static final int MAX_CLARIFICATION_ROUNDS = 3;

    private final AgentVerify agentVerify;
    private final PlanningService planningService;
    private final JsonPlanParser jsonPlanParser;
    private final JsonPlanExecutor jsonPlanExecutor;
    private final PermissionReviewService permissionReviewService;
    private final HttpAgentServerClient agentServerClient;
    private final LocalAuthorizationService localAuthorizationService;
    private final PlanBindingValidator planBindingValidator;
    private final ExecutionAuditService executionAuditService;
    private final PermissionAuditOutboxService permissionAuditOutboxService;
    private final RequestExecutionContextHolder executionContextHolder;
    private final PrivateMcpToolSchemaRegistry schemaRegistry;
    private final ObjectProvider<SemanticModelSnapshotProvider> semanticModelProvider;
    private final RuntimeCatalog runtimeCatalog;
    private final ClarificationElicitationService clarificationElicitationService;

    /**
     * 注入完整执行链路依赖。
     *
     * @param agentVerify Agent 来源校验器
     * @param planningService 云端规划服务
     * @param jsonPlanParser 计划解析器
     * @param jsonPlanExecutor 计划执行器
     * @param permissionReviewService 权限审查服务
     * @param agentServerClient Tenant 客户端
     * @param localAuthorizationService 本地机械授权服务
     * @param planBindingValidator 计划绑定校验器
     * @param executionAuditService 本地审计服务
     * @param permissionAuditOutboxService Tenant 审计 Outbox
     * @param executionContextHolder 请求上下文持有器
     * @param schemaRegistry 本地能力注册表
     * @param semanticModelProvider 客户语义模型提供器
     * @param runtimeCatalog Runtime Catalog module
     * @param clarificationElicitationService MCP 澄清交互服务
     */
    public CommonInternalTool(
            AgentVerify agentVerify,
            PlanningService planningService,
            JsonPlanParser jsonPlanParser,
            JsonPlanExecutor jsonPlanExecutor,
            PermissionReviewService permissionReviewService,
            HttpAgentServerClient agentServerClient,
            LocalAuthorizationService localAuthorizationService,
            PlanBindingValidator planBindingValidator,
            ExecutionAuditService executionAuditService,
            PermissionAuditOutboxService permissionAuditOutboxService,
            RequestExecutionContextHolder executionContextHolder,
            PrivateMcpToolSchemaRegistry schemaRegistry,
            ObjectProvider<SemanticModelSnapshotProvider> semanticModelProvider,
            RuntimeCatalog runtimeCatalog,
            ClarificationElicitationService clarificationElicitationService
    ) {
        this.agentVerify = agentVerify;
        this.planningService = planningService;
        this.jsonPlanParser = jsonPlanParser;
        this.jsonPlanExecutor = jsonPlanExecutor;
        this.permissionReviewService = permissionReviewService;
        this.agentServerClient = agentServerClient;
        this.localAuthorizationService = localAuthorizationService;
        this.planBindingValidator = planBindingValidator;
        this.executionAuditService = executionAuditService;
        this.permissionAuditOutboxService = permissionAuditOutboxService;
        this.executionContextHolder = executionContextHolder;
        this.schemaRegistry = schemaRegistry;
        this.semanticModelProvider = semanticModelProvider;
        this.runtimeCatalog = runtimeCatalog;
        this.clarificationElicitationService = clarificationElicitationService;
    }

    /**
     * 根据客户 MCP Tool 提交的自然语言意图执行完整只读安全链路。
     *
     * @param intent 客户 Tool 组装后的员工自然语言意图
     * @param requestContext MCP 请求上下文
     * @return 完整查询结果或安全失败提示
     */
    public Map<Boolean, String> execute(String intent, McpSyncRequestContext requestContext) {
        Instant startedAt = Instant.now();
        try {
            VerifiedEmployee verifiedEmployee = agentVerify.verifySourceAndGetEmployee(requestContext);
            if (verifiedEmployee == null) {
                return Map.of(false, "身份校验失败");
            }
            EmployeeAuth employeeAuth = verifiedEmployee.employeeAuth();
            String clarifiedIntent = intent;
            for (int round = 0; round <= MAX_CLARIFICATION_ROUNDS; round++) {
                try {
                    return executeAttempt(clarifiedIntent, requestContext, verifiedEmployee, employeeAuth, startedAt);
                } catch (ClarificationRequiredException exception) {
                    if (round >= MAX_CLARIFICATION_ROUNDS) {
                        notifyWarning(requestContext, "需要确认的信息过多，本次未执行查询");
                        return Map.of(true, "需要确认的信息超过 " + MAX_CLARIFICATION_ROUNDS + " 轮，本次未执行查询");
                    }
                    ClarificationRequest clarification = exception.getRequest();
                    SemanticModelSnapshot currentModel = requireSemanticModel();
                    if (!Objects.equals(currentModel.digest(), clarification.mdlDigest())) {
                        notifyWarning(requestContext, "语义模型已更新，请重新发起查询");
                        return Map.of(true, "语义模型已更新，原澄清选项已经失效，请重新发起查询");
                    }
                    notifyInfo(requestContext, "查询存在多种业务含义，正在请求确认");
                    ClarificationResolution resolution = clarificationElicitationService.elicit(
                            requestContext, clarification);
                    executionAuditService.recordClarification(verifiedEmployee.tenantId(),
                            employeeAuth.employeeId(), clarification, resolution);
                    if (!resolution.accepted()) {
                        notifyInfo(requestContext, "用户已取消本次查询");
                        return Map.of(true, "已取消确认，本次未执行查询");
                    }
                    if (!Objects.equals(requireSemanticModel().digest(), clarification.mdlDigest())) {
                        notifyWarning(requestContext, "语义模型已更新，请重新发起查询");
                        return Map.of(true, "语义模型已更新，原澄清选项已经失效，请重新发起查询");
                    }
                    clarifiedIntent = appendClarification(clarifiedIntent, clarification, resolution);
                }
            }
            throw new IllegalStateException("Clarification loop terminated unexpectedly");
        } catch (Exception exception) {
            notifyError(requestContext, "执行失败，未返回部分业务结果");
            var errId = UUID.randomUUID().toString();
            log.error("客户 MCP 执行失败: ID={}, Message={}", errId, exception.getMessage(), exception);
            return Map.of(false, "查询失败，请联系管理员查看本地审计: " + errId);
        }
    }

    private Map<Boolean, String> executeAttempt(String intent, McpSyncRequestContext requestContext,
                                                VerifiedEmployee verifiedEmployee, EmployeeAuth employeeAuth,
                                                Instant startedAt) throws Exception {

        notifyInfo(requestContext, "正在生成执行计划");
        String rawPlan = planningService.plan(intent, verifiedEmployee.tenantId(), employeeAuth.employeeId());
        JsonExecutionPlan executionPlan = jsonPlanParser.parse(rawPlan);
        planBindingValidator.validate(executionPlan, verifiedEmployee.tenantId(), employeeAuth.employeeId());
        if (executionPlan.hasError()) {
            return Map.of(true, GsonUtil.toJson(executionPlan));
        }

        // 在调用权限模型前先执行确定性绑定和只读校验，避免非法计划消耗员工模型额度。
        localAuthorizationService.authorize(executionPlan);
        boolean semanticPlan = containsExecutor(executionPlan.steps(), ExecutorType.WREN);
        runtimeCatalog.requireExecutable(executionPlan);
        SemanticModelSnapshot semanticModel = semanticPlan ? requireSemanticModel() : SemanticModelSnapshot.empty();
        runtimeCatalog.requireExecutable(executionPlan);
        boolean requiresReview = permissionReviewService.requiresReview(employeeAuth.permissionConfig());
        EmployeeLlmInvocationConfig llmConfig = requiresReview || semanticPlan
                ? agentServerClient.fetchEmployeeLlmConfig(verifiedEmployee.tenantId(), employeeAuth.employeeId())
                : null;
        if (requiresReview) {
            notifyInfo(requestContext, "正在校验数据访问权限");
        }
        PermissionReviewResult reviewResult = permissionReviewService.review(intent, executionPlan,
                employeeAuth.permissionConfig(), llmConfig, semanticModel);

        Map<String, Object> auditSummary = buildAuditSummary(executionPlan, reviewResult, employeeAuth,
                verifiedEmployee.tenantId(), semanticModel.digest());
        // 本地审计必须在任何数据库或 API 调用前成功落盘。
        executionAuditService.recordDecision(auditSummary);
        permissionAuditOutboxService.enqueue(auditSummary);
        if (!"allow".equalsIgnoreCase(reviewResult.decision())) {
            notifyWarning(requestContext, reviewResult.userMessage());
            return reviewResult.userMessage() == null || reviewResult.userMessage().isBlank()
                    ? Map.of(false, "身份校验失败") : Map.of(true, reviewResult.userMessage());
        }

        RequestExecutionContext executionContext = new RequestExecutionContext(
                llmConfig, reviewResult, semanticModel.digest());
        try (RequestExecutionContextHolder.Scope ignored = executionContextHolder.bind(executionContext)) {
            var executionResult = jsonPlanExecutor.execute(executionPlan, progressListener(requestContext));
            executionAuditService.record(executionPlan, executionResult);
            Object maskedResult = permissionReviewService.maskFinalResult(
                    executionResult.finalResult(), reviewResult);
            runtimeCatalog.requireExecutable(executionPlan);
            ExecutionReceipt receipt = new ExecutionReceipt(
                    executionPlan.planId(), "SUCCEEDED", executionPlan.asOf(),
                    executionPlan.consistencyMode(), operationDescriptions(executionPlan.steps()),
                    executionResult.executedSteps().size(), startedAt.toString(), Instant.now().toString());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("data", maskedResult);
            response.put("executionReceipt", receipt);
            return Map.of(true, GsonUtil.toJson(response));
        }
    }

    private String appendClarification(String intent, ClarificationRequest request,
                                       ClarificationResolution resolution) {
        return intent + "\n\n[用户澄清确认]\n"
                + "clarificationId: " + request.id() + "\n"
                + "sourceKey: " + request.sourceKey() + "\n"
                + resolution.confirmation();
    }

    private ExecutionProgressListener progressListener(McpSyncRequestContext requestContext) {
        return new ExecutionProgressListener() {
            @Override
            public void planStarted(int totalSteps) {
                notifyInfo(requestContext, "开始执行查询，共 " + totalSteps + " 个业务步骤");
                notifyProgress(requestContext, 0);
            }

            @Override
            public void stepStarted(int currentStep, int totalSteps) {
                notifyInfo(requestContext, "正在处理第 " + currentStep + "/" + totalSteps + " 个业务步骤");
            }

            @Override
            public void foreachStarted(int totalItems) {
                notifyInfo(requestContext, "目标系统不支持批量接口，将逐项处理 " + totalItems + " 项数据");
            }

            @Override
            public void foreachProgress(int completedItems, int totalItems) {
                int interval = Math.max(1, totalItems / 10);
                if (completedItems == totalItems || completedItems == 1 || completedItems % interval == 0) {
                    notifyInfo(requestContext, "逐项处理进度 " + completedItems + "/" + totalItems);
                    notifyProgress(requestContext, (int) Math.round(completedItems * 100.0 / totalItems));
                }
            }

            @Override
            public void planCompleted() {
                notifyProgress(requestContext, 100);
                notifyInfo(requestContext, "查询执行完成，正在整理结果");
            }
        };
    }

    private void notifyInfo(McpSyncRequestContext requestContext, String message) {
        try {
            requestContext.info(message);
        } catch (RuntimeException exception) {
            log.debug("Agent 不支持 info 进度通知: type={}", exception.getClass().getSimpleName());
        }
    }

    private void notifyWarning(McpSyncRequestContext requestContext, String message) {
        try {
            requestContext.warn(message == null || message.isBlank() ? "当前请求未通过权限校验" : message);
        } catch (RuntimeException exception) {
            log.debug("Agent 不支持 warn 进度通知: type={}", exception.getClass().getSimpleName());
        }
    }

    private void notifyError(McpSyncRequestContext requestContext, String message) {
        try {
            requestContext.error(message);
        } catch (RuntimeException exception) {
            log.debug("Agent 不支持 error 进度通知: type={}", exception.getClass().getSimpleName());
        }
    }

    private void notifyProgress(McpSyncRequestContext requestContext, int percent) {
        try {
            requestContext.progress(percent);
        } catch (RuntimeException exception) {
            log.debug("Agent 不支持 progress 通知: type={}", exception.getClass().getSimpleName());
        }
    }

    private SemanticModelSnapshot requireSemanticModel() {
        SemanticModelSnapshotProvider provider = semanticModelProvider.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("Wren semantic model provider is unavailable");
        }
        SemanticModelSnapshot snapshot = provider.snapshot();
        if (snapshot == null || snapshot.digest() == null || snapshot.digest().isBlank()) {
            throw new IllegalStateException("Wren semantic model digest is unavailable");
        }
        return snapshot;
    }

    private boolean containsExecutor(List<JsonExecutionStep> steps, ExecutorType executorType) {
        for (JsonExecutionStep step : steps) {
            if (step.isCall() && step.capabilityId() != null
                    && schemaRegistry.getCapability(step.capabilityId())
                    .map(capability -> executorType == capability.executorType()).orElse(false)) {
                return true;
            }
            if (containsExecutor(step.body(), executorType)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> buildAuditSummary(JsonExecutionPlan plan, PermissionReviewResult reviewResult,
                                                   EmployeeAuth employeeAuth, String tenantId, String mdlDigest) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("planId", plan.planId());
        audit.put("tenantId", tenantId);
        audit.put("employeeId", employeeAuth.employeeId());
        audit.put("permissionVersion", employeeAuth.versionNumber());
        audit.put("planContractVersion", plan.planContractVersion());
        audit.put("catalogDigest", plan.catalogDigest());
        audit.put("mdlDigest", mdlDigest);
        audit.put("capabilityIds", capabilityIds(plan.steps()));
        audit.put("decision", reviewResult.decision());
        audit.put("uncertain", reviewResult.uncertain());
        audit.put("matchedRuleIds", reviewResult.matchedRuleIds());
        audit.put("reviewVersion", permissionReviewService.reviewVersion());
        return audit;
    }

    private List<String> capabilityIds(List<JsonExecutionStep> steps) {
        List<String> capabilityIds = new ArrayList<>();
        for (JsonExecutionStep step : steps) {
            if (step.isCall() && step.capabilityId() != null) {
                capabilityIds.add(step.capabilityId());
            }
            capabilityIds.addAll(capabilityIds(step.body()));
        }
        return List.copyOf(capabilityIds);
    }

    private List<String> operationDescriptions(List<JsonExecutionStep> steps) {
        List<String> descriptions = new ArrayList<>();
        for (JsonExecutionStep step : steps) {
            if (step.isCall() && step.capabilityId() != null) {
                String description = schemaRegistry.getCapability(step.capabilityId())
                        .flatMap(capability -> schemaRegistry.getMethodSchema(capability.methodKey()))
                        .map(schema -> schema.description() == null || schema.description().isBlank()
                                ? "读取业务数据" : schema.description())
                        .orElse("读取业务数据");
                descriptions.add(description);
            } else if (step.isForeach()) {
                descriptions.add("逐项读取业务数据");
                descriptions.addAll(operationDescriptions(step.body()));
            }
        }
        return List.copyOf(descriptions);
    }

}
