package com.kevin.mcp.agent.model;

/**
 * 承载当前员工请求级 LLM 调用配置。
 *
 * <p>该对象只允许存在于本地请求上下文，禁止序列化到计划、Catalog、审计或日志。</p>
 *
 * @param baseUrl 员工 NewAPI 地址
 * @param apiKey 员工专属 API Key
 * @param generationModel 员工使用的生成模型
 * @param embeddingModel 员工使用的向量模型，可为空
 * @param rerankModel 员工使用的重排模型，可为空
 * @author Kevin
 * @date 2026-09-17
 */
public record EmployeeLlmInvocationConfig(
        String baseUrl,
        String apiKey,
        String generationModel,
        String embeddingModel,
        String rerankModel
) {

    /**
     * 创建仅包含生成模型的调用配置，供不需要向量能力的调用与测试使用。
     *
     * @param baseUrl 员工 NewAPI 地址
     * @param apiKey 员工专属 API Key
     * @param generationModel 生成模型
     */
    public EmployeeLlmInvocationConfig(String baseUrl, String apiKey, String generationModel) {
        this(baseUrl, apiKey, generationModel, null, null);
    }

    /**
     * 返回生成模型名称，兼容仅使用 Chat Completions 的现有调用方。
     *
     * @return 生成模型名称
     */
    public String model() {
        return generationModel;
    }

    @Override
    public String toString() {
        return "EmployeeLlmInvocationConfig{baseUrl='" + baseUrl + "', generationModel='"
                + generationModel + "', embeddingModel='" + embeddingModel + "', rerankModel='"
                + rerankModel + "', apiKey='***'}";
    }
}
