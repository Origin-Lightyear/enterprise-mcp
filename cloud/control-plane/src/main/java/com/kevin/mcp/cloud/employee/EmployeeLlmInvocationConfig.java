package com.kevin.mcp.cloud.employee;

/**
 * 承载单次云端规划使用的不可变员工 LLM 连接信息，防止并发请求串用密钥。
 *
 * @param baseUrl NewAPI 基础地址
 * @param apiKey 员工专属 API Key
 * @param generationModel 本次规划使用的生成模型
 * @param embeddingModel 员工可用的向量模型，可为空
 * @param rerankModel 员工可用的重排模型，可为空
 * @author Kevin
 * @date 2026-09-10
 */
public record EmployeeLlmInvocationConfig(
        String baseUrl,
        String apiKey,
        String generationModel,
        String embeddingModel,
        String rerankModel
) {

    /**
     * 创建仅包含生成模型的规划配置，供不需要向量能力的调用与测试使用。
     *
     * @param baseUrl NewAPI 基础地址
     * @param apiKey 员工专属 API Key
     * @param generationModel 生成模型
     */
    public EmployeeLlmInvocationConfig(String baseUrl, String apiKey, String generationModel) {
        this(baseUrl, apiKey, generationModel, null, null);
    }

    /**
     * 返回生成模型名称，兼容云端规划调用边界。
     *
     * @return 生成模型名称
     */
    public String model() {
        return generationModel;
    }

    /**
     * 返回脱敏后的调用配置摘要，避免 API Key 进入日志和异常诊断。
     *
     * @return 不包含 API Key 的配置摘要
     */
    @Override
    public String toString() {
        return "EmployeeLlmInvocationConfig{baseUrl='" + baseUrl + "', generationModel='"
                + generationModel + "', embeddingModel='" + embeddingModel + "', rerankModel='"
                + rerankModel + "', apiKey='***'}";
    }
}
