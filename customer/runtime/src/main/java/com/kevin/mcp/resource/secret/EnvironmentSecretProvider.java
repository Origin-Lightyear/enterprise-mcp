package com.kevin.mcp.resource.secret;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 从环境变量或 JVM 属性解析开发和单机部署密钥。
 *
 * <p>生产环境可以以自定义 Bean 替换该实现，避免将密码写入 application.yml。</p>
 *
 * @author Kevin
 * @date 2026-09-09
 */
@Component
public class EnvironmentSecretProvider implements SecretProvider {

    /** {@inheritDoc} */
    @Override
    public Optional<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String value = System.getProperty(reference);
        if (value == null || value.isBlank()) {
            value = System.getenv(reference);
        }
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
