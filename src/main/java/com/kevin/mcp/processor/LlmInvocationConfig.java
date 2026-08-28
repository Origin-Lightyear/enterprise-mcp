package com.kevin.mcp.processor;

/**
 * 承载单次 MCP 请求使用的不可变 LLM 连接信息，防止并发请求串用员工密钥。
 *
 * @param baseUrl NewAPI 基础地址
 * @param apiKey 员工专属 API Key
 * @param model 本次请求使用的模型
 * @author Kevin
 * @date 2026-08-10
 */
public record LlmInvocationConfig(String baseUrl, String apiKey, String model) {

    /**
     * 返回脱敏后的调用配置摘要，避免 API Key 进入日志。
     *
     * @return 不包含 API Key 的配置摘要
     */
    @Override
    public String toString() {
        return "LlmInvocationConfig{baseUrl='" + baseUrl + "', model='" + model + "', apiKey='***'}";
    }
}
