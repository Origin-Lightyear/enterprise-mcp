package com.kevin.mcp.cloud.employee;

/**
 * 承载 Tenant 返回的员工专属 LLM 配置，避免不同租户或员工共用访问密钥。
 *
 * @param apiKey 员工对应的 NewAPI API Key
 * @param newapiUrl NewAPI 基础地址
 * @param model Tenant 显式指定的模型，可为空
 * @author Kevin
 * @date 2026-09-10
 */
public record EmployeeLlmConfig(String apiKey, String newapiUrl, String model) {

    /**
     * 返回脱敏后的配置摘要，避免 API Key 进入日志和异常诊断。
     *
     * @return 不包含 API Key 的配置摘要
     */
    @Override
    public String toString() {
        return "EmployeeLlmConfig{newapiUrl='" + newapiUrl + "', model='" + model + "', apiKey='***'}";
    }
}
