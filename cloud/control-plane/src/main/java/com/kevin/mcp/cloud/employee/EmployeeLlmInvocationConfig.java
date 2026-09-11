package com.kevin.mcp.cloud.employee;

/**
 * 承载单次云端规划使用的不可变员工 LLM 连接信息，防止并发请求串用密钥。
 *
 * @param baseUrl NewAPI 基础地址
 * @param apiKey 员工专属 API Key
 * @param model 本次规划使用的模型
 * @author Kevin
 * @date 2026-09-10
 */
public record EmployeeLlmInvocationConfig(String baseUrl, String apiKey, String model) {

    /**
     * 返回脱敏后的调用配置摘要，避免 API Key 进入日志和异常诊断。
     *
     * @return 不包含 API Key 的配置摘要
     */
    @Override
    public String toString() {
        return "EmployeeLlmInvocationConfig{baseUrl='" + baseUrl + "', model='" + model + "', apiKey='***'}";
    }
}
