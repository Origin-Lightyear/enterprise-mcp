package com.kevin.mcp.agent.connector.entity;

/**
 * 承载 Platform 返回的员工专属 LLM 配置，避免不同员工共用访问密钥。
 *
 * @param apiKey 员工对应的 NewAPI API Key
 * @param newapiUrl NewAPI 基础地址
 * @author Kevin
 * @date 2026-08-10
 */
public record EmployeeLlmConfig(String apiKey, String newapiUrl) {

    /**
     * 返回脱敏后的配置摘要，避免日志或异常诊断意外泄露 API Key。
     *
     * @return 不包含 API Key 的配置摘要
     */
    @Override
    public String toString() {
        return "EmployeeLlmConfig{newapiUrl='" + newapiUrl + "', apiKey='***'}";
    }
}
