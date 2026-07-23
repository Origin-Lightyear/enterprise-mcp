package com.kevin.mcp.tools;

import com.kevin.mcp.service.RAGService;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 对 Agent暴露的知识库 MCP Server Tool
 *
 * @author Kevin
 * 2026/7/9
 */
@Component
public class CommonRagTool {
    private static final Logger log = LoggerFactory.getLogger(CommonRagTool.class);

    RAGService ragService;

    public CommonRagTool(RAGService ragService) {
        this.ragService = ragService;
    }

    @McpTool(
            name = "rag_query_management",
            description = "查询企业内部企业治理与基础管理知识库,包含:制度与规范/组织与人事/行政与后勤/财务与法务",
            generateOutputSchema = true
    )
    public String rag_query_management( @McpToolParam(description = "自然语言") String message) {
        return GsonUtil.toJson(ragService.query(message));
    }

    @McpTool(
            name = "rag_query_market",
            description = "查询企业内部业务运营与市场知识库,包含:市场营销与品牌/销售与商务/产品与研发/供应链与采购/客户服务与支持",
            generateOutputSchema = true
    )
    public String rag_query_market( @McpToolParam(description = "自然语言") String message) {
        return GsonUtil.toJson(ragService.query(message));
    }

    @McpTool(
            name = "rag_query_technology",
            description = "查询企业内部技术与数字化知识库,包含:IT运维与基础设施/数据与信息安全/研发技术文档",
            generateOutputSchema = true
    )
    public String rag_query_technology( @McpToolParam(description = "自然语言") String message) {
        return GsonUtil.toJson(ragService.query(message));
    }

    @McpTool(
            name = "rag_query_project",
            description = "查询企业内部项目管理与交付知识库,包含:项目管理/项目复盘与案例库",
            generateOutputSchema = true
    )
    public String rag_query_project( @McpToolParam(description = "自然语言") String message) {
        return GsonUtil.toJson(ragService.query(message));
    }

    @McpTool(
            name = "rag_query_study",
            description = "查询企业内部学习与知识传承知识库,包含:培训与发展/经验与案例/常见问题（FAQ）与自助服务",
            generateOutputSchema = true
    )
    public String rag_query_study( @McpToolParam(description = "自然语言") String message) {
        return GsonUtil.toJson(ragService.query(message));
    }

    @McpTool(
            name = "rag_query_environment",
            description = "查询企业内部外部环境与资源知识库,包含:行业与政策/合作伙伴与生态",
            generateOutputSchema = true
    )
    public String rag_query_environment( @McpToolParam(description = "自然语言") String message) {
        return GsonUtil.toJson(ragService.query(message));
    }

    @McpTool(
            name = "rag_query_culture",
            description = "查询企业内部企业文化与内部沟通知识库,包含:企业文化/内部沟通与公告",
            generateOutputSchema = true
    )
    public String rag_query_culture( @McpToolParam(description = "自然语言") String message) {
        return GsonUtil.toJson(ragService.query(message));
    }

}
