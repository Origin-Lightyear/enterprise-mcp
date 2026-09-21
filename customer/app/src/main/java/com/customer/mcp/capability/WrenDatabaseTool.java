package com.customer.mcp.capability;

import com.customer.mcp.wren.WrenSidecarClient;
import com.kevin.mcp.clarification.ClarificationRequest;
import com.kevin.mcp.clarification.ClarificationRequiredException;
import com.kevin.mcp.annotation.BatchMode;
import com.kevin.mcp.annotation.ExecutorType;
import com.kevin.mcp.annotation.LocalCapability;
import com.kevin.mcp.annotation.OperationMode;
import com.kevin.mcp.annotation.PrivateMcpTool;
import com.kevin.mcp.annotation.PrivateMcpToolParam;
import com.kevin.mcp.execution.RequestExecutionContext;
import com.kevin.mcp.execution.RequestExecutionContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将客户数据库语义查询委托给本机 Wren，不在 customerapp 内维护 JDBC 连接。
 *
 * @author Kevin
 * @date 2026-09-12
 */
@Component
public class WrenDatabaseTool {

    private final WrenSidecarClient client;
    private final RequestExecutionContextHolder executionContextHolder;

    /**
     * 注入 Wren 本机客户端。
     *
     * @param client Wren 客户端
     * @param executionContextHolder Runtime 请求上下文
     */
    public WrenDatabaseTool(WrenSidecarClient client,
                            RequestExecutionContextHolder executionContextHolder) {
        this.client = client;
        this.executionContextHolder = executionContextHolder;
    }

    /**
     * 使用客户语义模型执行数据库查询。
     *
     * @param sourceKey Current MDL 中数据源的稳定标识；一次查询只能使用一个数据源
     * @param question 自然语言查询问题
     * @param outputMode 输出模式，例如 table 或 summary
     * @return Wren 返回的结构化查询结果
     */
    @PrivateMcpTool(
            description = "使用客户数据库语义模型执行查询和报表分析",
            generateOutputSchema = true
    )
    @LocalCapability(
            id = "database.semantic-query",
            version = 1,
            mode = OperationMode.READ_ONLY,
            executorType = ExecutorType.WREN,
            batchMode = BatchMode.SINGLE,
            dataDomain = "CUSTOMER_DATABASE"
    )
    public Map<?, ?> query(
            @PrivateMcpToolParam(description = "Current MDL 数据源标识；必须与本次查询使用的 Models 属于同一数据源", required = true)
            String sourceKey,
            @PrivateMcpToolParam(description = "自然语言查询问题", required = true) String question,
            @PrivateMcpToolParam(description = "输出模式，例如 table 或 summary", required = false) String outputMode
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        RequestExecutionContext context = executionContextHolder.requireContext();
        if (context.llmConfig() == null) {
            throw new IllegalStateException("Employee LLM configuration is unavailable for Wren query");
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey is required for Wren query");
        }
        request.put("sourceKey", sourceKey);
        request.put("question", question);
        request.put("outputMode", outputMode == null || outputMode.isBlank() ? "table" : outputMode);
        request.put("expectedMdlDigest", context.mdlDigest());
        request.put("llm", Map.of(
                "baseUrl", context.llmConfig().baseUrl(),
                "apiKey", context.llmConfig().apiKey(),
                "generationModel", context.llmConfig().generationModel(),
                "embeddingModel", context.llmConfig().embeddingModel() == null ? "" : context.llmConfig().embeddingModel(),
                "rerankModel", context.llmConfig().rerankModel() == null ? "" : context.llmConfig().rerankModel()
        ));
        Map<?, ?> response = client.query(request);
        Map<?, ?> data = response.get("data") instanceof Map<?, ?> responseData ? responseData : response;
        if ("NEEDS_CLARIFICATION".equals(data.get("outcome"))) {
            throw new ClarificationRequiredException(toClarificationRequest(data));
        }
        return response;
    }

    private ClarificationRequest toClarificationRequest(Map<?, ?> response) {
        if (!(response.get("clarification") instanceof Map<?, ?> clarification)) {
            throw new IllegalStateException("Wren clarification response is invalid");
        }
        Object rawOptions = clarification.get("options");
        if (!(rawOptions instanceof List<?> options) || options.size() < 2 || options.size() > 5) {
            throw new IllegalStateException("Wren clarification options are invalid");
        }
        List<ClarificationRequest.ClarificationOption> normalizedOptions = options.stream()
                .map(this::toClarificationOption)
                .toList();
        return new ClarificationRequest(
                requireText(clarification.get("id"), "clarification.id"),
                requireText(clarification.get("question"), "clarification.question"),
                requireText(clarification.get("reason"), "clarification.reason"),
                normalizedOptions,
                Boolean.TRUE.equals(clarification.get("allowFreeText")),
                requireText(response.get("sourceKey"), "sourceKey"),
                requireText(response.get("mdlDigest"), "mdlDigest")
        );
    }

    private ClarificationRequest.ClarificationOption toClarificationOption(Object value) {
        if (!(value instanceof Map<?, ?> option)) {
            throw new IllegalStateException("Wren clarification option is invalid");
        }
        return new ClarificationRequest.ClarificationOption(
                requireText(option.get("id"), "clarification.options.id"),
                requireText(option.get("label"), "clarification.options.label")
        );
    }

    private String requireText(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Wren clarification field is invalid: " + field);
        }
        return text;
    }
}
