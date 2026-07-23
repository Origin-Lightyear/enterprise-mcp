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
 * 先让模型基于注册表提供的精简方法描述生成调用计划，再交给执行器按 methodKey 落地执行。
 *
 * @author Kevin
 * 2026/7/21
 */
@Service
public class LLMService {
    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    LLMProcessor llmProcessor;
    PrivateMcpToolSchemaRegistry privateMcpToolSchemaRegistry;
    LLMService(
            LLMProcessor llmProcessor,
            PrivateMcpToolSchemaRegistry privateMcpToolSchemaRegistry
    ) {
        this.llmProcessor = llmProcessor;
        this.privateMcpToolSchemaRegistry = privateMcpToolSchemaRegistry;
    }

    /**
     * 生成方法调用计划并执行，返回最终执行结果 JSON。
     *
     * @param userPrompt 用户原始输入
     * @return 最终执行结果 JSON
     * @throws Exception 模型请求或计划执行失败
     */
    public String chat(String userPrompt) throws Exception {
        // 获取内部注册Tool的JSONSchema
        String planningSchemas = GsonUtil.toJson(this.privateMcpToolSchemaRegistry.getPlanningSchemas());
        // 构建LLM消息
        String queryMsg = MessageFormat.format(PromptContext.QUERY, userPrompt, planningSchemas, PromptContext.OUTPUT_SCHEMA);
        // 请求LLM, 拿到模型结果(调用执行计划的JSONSchema)
        log.debug("LLM 用户意图: {} 构建请求: {}", userPrompt, queryMsg);
        String result = llmProcessor.chat(queryMsg);
        // TODO LLM单点依赖/降级逻辑
        log.debug("LLM 用户意图: {} 返回: {}", userPrompt, result);
        return result;
    }
}
