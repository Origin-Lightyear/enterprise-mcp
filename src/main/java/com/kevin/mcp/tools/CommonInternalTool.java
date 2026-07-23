package com.kevin.mcp.tools;

import com.kevin.mcp.agent.connector.AgentVerify;
import com.kevin.mcp.processor.JsonPlanExecutor;
import com.kevin.mcp.processor.JsonPlanParser;
import com.kevin.mcp.service.LLMService;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * 对 Agent暴露的 企业内部系统 MCP Server Tool
 * Internal 里面的 MCP Tool，用于内部调用，不暴露给外部。
 *
 * @author Kevin
 * 2026/7/9
 */
@Component
public class CommonInternalTool {
    private static final Logger log = LoggerFactory.getLogger(CommonInternalTool.class);

    AgentVerify agentVerify;
    LLMService llmService;
    JsonPlanParser jsonPlanParser;
    JsonPlanExecutor jsonPlanExecutor;

    public CommonInternalTool(AgentVerify agentVerify, LLMService llmService, JsonPlanParser jsonPlanParser, JsonPlanExecutor jsonPlanExecutor) {
        this.agentVerify = agentVerify;
        this.llmService = llmService;
        this.jsonPlanParser = jsonPlanParser;
        this.jsonPlanExecutor = jsonPlanExecutor;
    }


    @McpTool(
            name = "select_all",
            description = "查询企业内部各系统的数据,如报表/销售/客户/订单/门店/员工/部门/财务/产品等任何数据",
            generateOutputSchema = true
    )
    public String selectAll(
            @McpToolParam(description = "查询的内容") String message,
            McpSyncRequestContext requestContext
    ) {
        log.info("selectAll: {}",  message);
        try {
            // 鉴权来源系统
            if (!agentVerify.verifySource(requestContext)) {
                return "Unauthorized";
            }

            String result = llmService.chat(message);

            // 解析执行计划的JSONSchema
            var executionPlan = jsonPlanParser.parse(result);
            // 开始执行计划,拿到最终执行结果
            var executionResult = jsonPlanExecutor.execute(executionPlan);
            // TODO 事务/补偿机制
            log.info("PlanExecutor 执行结果: {}", GsonUtil.toJson(executionResult));

            // TODO 缓存用户的意图和参数
            return GsonUtil.toJson(executionResult.finalResult());
        } catch (Exception e) {
            log.error("selectAll error: {}", e.getMessage());
            return "查询失败";
        }
    }
}
