package com.kevin.mcp.tools;

import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.identity.AgentVerify;
import com.kevin.mcp.agent.identity.VerifiedEmployee;
import com.kevin.mcp.agent.model.EmployeeAuth;
import com.kevin.mcp.audit.ExecutionAuditService;
import com.kevin.mcp.authorization.LocalAuthorizationService;
import com.kevin.mcp.authorization.PermissionReviewResult;
import com.kevin.mcp.authorization.PermissionReviewService;
import com.kevin.mcp.execution.JsonPlanExecutor;
import com.kevin.mcp.execution.JsonPlanParser;
import com.kevin.mcp.planning.PlanningService;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行客户 MCP Tool 共用的认证、规划、授权、执行、脱敏和审计流程。
 *
 * <p>本类不声明 {@code @McpTool}，客户应在 {@code customer/app} 定义自己的 MCP 契约后调用
 * {@link #execute(String, McpSyncRequestContext)}，从而允许不同客户使用不同的 Tool 名称、参数和描述。</p>
 *
 * @author Kevin
 * @date 2026-07-29
 */
@Component
public class CommonInternalTool {
    private static final Logger log = LoggerFactory.getLogger(CommonInternalTool.class);

    private final AgentVerify agentVerify;
    private final PlanningService planningService;
    private final JsonPlanParser jsonPlanParser;
    private final JsonPlanExecutor jsonPlanExecutor;
    private final PermissionReviewService permissionReviewService;
    private final HttpAgentServerClient agentServerClient;
    private final LocalAuthorizationService localAuthorizationService = new LocalAuthorizationService();
    private final ExecutionAuditService executionAuditService;

    /**
     * 注入内部查询依赖。
     *
     * @param agentVerify Agent 来源校验器
     * @param planningService 云端规划服务
     * @param jsonPlanParser 执行计划解析器
     * @param jsonPlanExecutor 执行计划执行器
     * @param permissionReviewService 权限审查服务
     * @param agentServerClient AgentServer 客户端
     */
    public CommonInternalTool(AgentVerify agentVerify,
                              PlanningService planningService, JsonPlanParser jsonPlanParser,
                              JsonPlanExecutor jsonPlanExecutor, PermissionReviewService permissionReviewService,
                              HttpAgentServerClient agentServerClient, ExecutionAuditService executionAuditService) {
        this.agentVerify = agentVerify;
        this.planningService = planningService;
        this.jsonPlanParser = jsonPlanParser;
        this.jsonPlanExecutor = jsonPlanExecutor;
        this.permissionReviewService = permissionReviewService;
        this.agentServerClient = agentServerClient;
        this.executionAuditService = executionAuditService;
    }

    /**
     * 根据客户 MCP Tool 提交的自然语言意图执行完整安全链路。
     *
     * @param intent 客户 Tool 组装后的员工自然语言意图
     * @param requestContext MCP 请求上下文
     * @return 查询结果或权限拒绝提示
     */
    public String execute(String intent, McpSyncRequestContext requestContext) {
        log.debug("执行客户 MCP Tool 请求: {}", intent);
        try {
            // 验证签名, 并获取员工权限信息
            VerifiedEmployee verifiedEmployee = agentVerify.verifySourceAndGetEmployee(requestContext);
            if (verifiedEmployee == null) {
                return "Unauthorized";
            }
            EmployeeAuth employeeAuth = verifiedEmployee.employeeAuth();

            // Runtime 只向云端提交意图和部署身份，不在客户环境内持有模型密钥或生成计划。
            String result = planningService.plan(
                    intent, verifiedEmployee.tenantId(), employeeAuth.employeeId());

            // 生成执行计划
            var executionPlan = jsonPlanParser.parse(result);

            // 模型已明确声明无法生成可执行计划时，直接返回错误结果，避免继续进入执行器。
            if (executionPlan.hasError()) {
                log.info("LLM returned non-executable plan, intent: {}, error: {}", executionPlan.intent(), executionPlan.error());
                return GsonUtil.toJson(executionPlan);
            }

            // 权限审查
            PermissionReviewResult reviewResult = permissionReviewService.review(
                    executionPlan, employeeAuth.permissionConfig());

            // 提交审计报告
            reportAudit(intent, executionPlan, reviewResult, employeeAuth.permissionConfig(),
                    verifiedEmployee.tenantId(), employeeAuth.employeeId(), permissionReviewService.reviewVersion());

            if (!"allow".equalsIgnoreCase(reviewResult.decision())) {
                log.info("权限拒绝: {}", reviewResult.denyReason());
                return reviewResult.denyReason() == null || reviewResult.denyReason().isBlank()
                        ? "Unauthorized"
                        : reviewResult.denyReason();
            }

            // 云端签名只证明计划来源，本地仍必须按员工权限白名单逐能力硬校验。
            localAuthorizationService.authorize(executionPlan, employeeAuth.permissionConfig());

            // 执行计划
            var executionResult = jsonPlanExecutor.execute(executionPlan);
            executionAuditService.record(executionPlan, executionResult);
            log.info("PlanExecutor 执行完成: planId={}, success={}, stepCount={}",
                    executionResult.planId(), executionResult.success(), executionResult.executedSteps().size());

            // 敏感数据处理
            Object maskedResult = permissionReviewService.maskFinalResult(executionResult.finalResult(), reviewResult);
            return GsonUtil.toJson(maskedResult);
        } catch (Exception exception) {
            log.error("客户 MCP Tool 执行失败: {}", exception.getMessage());
            return "查询失败, 请联系管理员查看日志";
        }
    }


    /**
     * 提交审计报告。
     *
     * @param message 员工自然语言请求
     * @param executionPlan 执行计划
     * @param reviewResult 权限审查结果
     * @param permissionConfig 员工权限配置
     * @param tenantId 已验证的租户标识
     * @param employeeId 已验证的员工标识
     * @param model 本次请求使用的模型
     */
    private void reportAudit(String message, Object executionPlan, PermissionReviewResult reviewResult,
                             Map<String, Object> permissionConfig, String tenantId, String employeeId, String model) {
        try {
            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("tenantId", tenantId);
            auditPayload.put("employeeId", employeeId);
            auditPayload.put("requestMessage", message);
            auditPayload.put("planSummary", executionPlan);
            auditPayload.put("reviewResult", reviewResult);
            auditPayload.put("decision", reviewResult.decision());
            auditPayload.put("matchedRules", reviewResult.matchedDenyRules());
            auditPayload.put("modelVersion", model);
            agentServerClient.reportPermissionAudit(auditPayload);
        } catch (Exception exception) {
            log.warn("report permission audit failed: {}", exception.getMessage());
        }
    }
}
