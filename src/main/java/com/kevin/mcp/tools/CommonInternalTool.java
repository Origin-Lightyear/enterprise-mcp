package com.kevin.mcp.tools;

import com.kevin.mcp.agent.connector.AgentVerify;
import com.kevin.mcp.agent.connector.HttpAgentServerClient;
import com.kevin.mcp.agent.connector.entity.EmployeeAuth;
import com.kevin.mcp.processor.JsonPlanExecutor;
import com.kevin.mcp.processor.JsonPlanParser;
import com.kevin.mcp.processor.PermissionReviewResult;
import com.kevin.mcp.processor.PermissionReviewService;
import com.kevin.mcp.service.LLMService;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 暴露企业内部系统聚合查询 MCP Tool，并在内部执行计划落地前完成员工权限审查。
 *
 * @author Kevin
 * @date 2026-07-29
 */
@Component
public class CommonInternalTool {
    private static final Logger log = LoggerFactory.getLogger(CommonInternalTool.class);

    private final AgentVerify agentVerify;
    private final LLMService llmService;
    private final JsonPlanParser jsonPlanParser;
    private final JsonPlanExecutor jsonPlanExecutor;
    private final PermissionReviewService permissionReviewService;
    private final HttpAgentServerClient agentServerClient;

    /**
     * 注入内部查询依赖。
     *
     * @param agentVerify Agent 来源校验器
     * @param llmService 执行计划生成服务
     * @param jsonPlanParser 执行计划解析器
     * @param jsonPlanExecutor 执行计划执行器
     * @param permissionReviewService 权限审查服务
     * @param agentServerClient AgentServer 客户端
     */
    public CommonInternalTool(AgentVerify agentVerify, LLMService llmService, JsonPlanParser jsonPlanParser,
                              JsonPlanExecutor jsonPlanExecutor, PermissionReviewService permissionReviewService,
                              HttpAgentServerClient agentServerClient) {
        this.agentVerify = agentVerify;
        this.llmService = llmService;
        this.jsonPlanParser = jsonPlanParser;
        this.jsonPlanExecutor = jsonPlanExecutor;
        this.permissionReviewService = permissionReviewService;
        this.agentServerClient = agentServerClient;
    }

    /**
     * 查询企业内部系统数据。
     *
     * @param message 员工自然语言请求
     * @param requestContext MCP 请求上下文
     * @return 查询结果或权限拒绝提示
     */
    @McpTool(
            name = "select_all",
            description = "查询企业内部各系统数据，如报表、销售、客户、订单、门店、员工、部门、财务、产品等数据",
            generateOutputSchema = true
    )
    public String selectAll(
            @McpToolParam(description = "查询内容") String message,
            McpSyncRequestContext requestContext
    ) {
        log.debug("MCP SelectAll: {}", message);
        try {
            // 验证签名, 并获取员工权限信息
            EmployeeAuth employeeAuth = agentVerify.verifySourceAndGetAuth(requestContext);
            if (employeeAuth == null) {
                return "Unauthorized";
            }

            // 调用LLM
            String result = llmService.chat(message);

            // 生成执行计划
            var executionPlan = jsonPlanParser.parse(result);

            // 模型已明确声明无法生成可执行计划时，直接返回错误结果，避免继续进入执行器。
            if (executionPlan.hasError()) {
                log.info("LLM returned non-executable plan, intent: {}, error: {}", executionPlan.intent(), executionPlan.error());
                return GsonUtil.toJson(executionPlan);
            }

            // 权限审查
            PermissionReviewResult reviewResult = permissionReviewService.review(message, executionPlan, employeeAuth.permissionConfig());

            // 提交审计报告
            reportAudit(message, executionPlan, reviewResult, employeeAuth.permissionConfig());

            if (!"allow".equalsIgnoreCase(reviewResult.decision())) {
                log.info("权限拒绝: {}", reviewResult.denyReason());
                return reviewResult.denyReason() == null || reviewResult.denyReason().isBlank()
                        ? "Unauthorized"
                        : reviewResult.denyReason();
            }

            // 执行计划
            var executionResult = jsonPlanExecutor.execute(executionPlan);
            log.info("PlanExecutor 执行结果: {}", GsonUtil.toJson(executionResult));

            // 敏感数据处理
            Object maskedResult = permissionReviewService.maskFinalResult(executionResult.finalResult(), reviewResult);
            return GsonUtil.toJson(executionResult);
        } catch (Exception exception) {
            log.error("MCP SelectAll Error: {}", exception.getMessage());
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
     */
    private void reportAudit(String message, Object executionPlan, PermissionReviewResult reviewResult, Map<String, Object> permissionConfig) {
        try {
            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("tenantId", permissionConfig.get("tenantId"));
            auditPayload.put("employeeId", permissionConfig.get("employeeId"));
            auditPayload.put("requestMessage", message);
            auditPayload.put("planSummary", executionPlan);
            auditPayload.put("reviewResult", reviewResult);
            auditPayload.put("decision", reviewResult.decision());
            auditPayload.put("matchedRules", reviewResult.matchedDenyRules());
            auditPayload.put("modelVersion", llmService.getDefaultModel());
            agentServerClient.reportPermissionAudit(auditPayload);
        } catch (Exception exception) {
            log.warn("report permission audit failed: {}", exception.getMessage());
        }
    }
}
