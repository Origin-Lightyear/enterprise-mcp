package com.kevin.mcp.resource.secret;

import java.util.Optional;

/**
 * 解析 Runtime 配置中引用的敏感值。
 *
 * <p>连接器只持有引用名而不感知密钥存储实现，客户可替换此接口接入 Vault、Kubernetes Secret 或本地密码管理系统。</p>
 *
 * @author Kevin
 * @date 2026-09-09
 */
public interface SecretProvider {

    /**
     * 解析指定引用的敏感值。
     *
     * @param reference 配置中的密钥引用
     * @return 已解析的值；未配置时为空
     */
    Optional<String> resolve(String reference);
}
