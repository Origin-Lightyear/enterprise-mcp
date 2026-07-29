package com.kevin.mcp.service;

import com.kevin.mcp.context.PromptContext;
import com.kevin.mcp.processor.LLMProcessor;
import com.kevin.mcp.registry.PrivateMcpToolSchemaRegistry;
import com.kevin.mcp.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.MessageFormat;

/**
 * 协调 LLM 规划结果与内部方法执行。
 *
 * @author Kevin
 * @date 2026-07-21
 */
@Service
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    private final LLMProcessor llmProcessor;
    private final PrivateMcpToolSchemaRegistry privateMcpToolSchemaRegistry;

    /**
     * 注入 LLM 处理器和内部 Tool Schema 注册表。
     *
     * @param llmProcessor LLM 处理器
     * @param privateMcpToolSchemaRegistry 内部 Tool Schema 注册表
     */
    public LLMService(LLMProcessor llmProcessor, PrivateMcpToolSchemaRegistry privateMcpToolSchemaRegistry) {
        this.llmProcessor = llmProcessor;
        this.privateMcpToolSchemaRegistry = privateMcpToolSchemaRegistry;
    }

    /**
     * 生成内部方法调用执行计划。
     *
     * @param userPrompt 员工原始输入
     * @return 执行计划 JSON
     * @throws Exception LLM 请求失败或计划生成失败
     */
    public String chat(String userPrompt) throws Exception {
        String planningSchemas = GsonUtil.toJson(this.privateMcpToolSchemaRegistry.getPlanningSchemas());
        String queryMsg = MessageFormat.format(PromptContext.QUERY, userPrompt, planningSchemas, PromptContext.OUTPUT_SCHEMA);
        log.debug("LLM 用户意图: {} 构建请求: {}", userPrompt, queryMsg);
        String result = llmProcessor.chat(queryMsg);
        log.debug("LLM 用户意图: {} 返回: {}", userPrompt, result);
        return result;
    }

    /**
     * 获取当前规划链路使用的默认模型名称。
     *
     * @return 默认模型名称
     */
    public String getDefaultModel() {
        return llmProcessor.getModel();
    }
}
