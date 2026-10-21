package com.customer.mcp.k3.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 保存客户金蝶账套的连接配置，避免将凭证写入执行计划或知识缓存。
 *
 * @author Kevin
 * @date 2026-10-14
 */
@ConfigurationProperties(prefix = "enterprise.k3")
public class K3Properties {

    /** 显式启用金蝶能力，避免未配置客户看到不可用工具。 */
    private boolean enabled;

    /** 金蝶 WebAPI 根地址。 */
    private String serverUrl = "";
    /** 金蝶账套 ID。 */
    private String acctId = "";
    /** 登录模式，支持 appsecret 与 password。 */
    private String authMode = "appsecret";
    /** 金蝶应用 ID。 */
    private String appId = "";
    /** 金蝶应用密钥。 */
    private String appSecret = "";
    /** 金蝶登录用户。 */
    private String username = "";
    /** 金蝶登录密码。 */
    private String password = "";
    /** 金蝶语言 ID。 */
    private int lcid = 2052;
    /** 本地元数据缓存目录。 */
    private String metadataCacheDir = "./data/k3/metadata-cache";
    /** 本地知识目录。 */
    private String knowledgeDir = "./data/k3/learned";

    public boolean isConfigured() {
        return !serverUrl.isBlank() && !acctId.isBlank() && !username.isBlank()
                && ("password".equalsIgnoreCase(authMode)
                ? !password.isBlank() : !appId.isBlank() && !appSecret.isBlank());
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl == null ? "" : serverUrl; }
    public String getAcctId() { return acctId; }
    public void setAcctId(String acctId) { this.acctId = acctId == null ? "" : acctId; }
    public String getAuthMode() { return authMode; }
    public void setAuthMode(String authMode) { this.authMode = authMode == null ? "" : authMode; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId == null ? "" : appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret == null ? "" : appSecret; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username == null ? "" : username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password == null ? "" : password; }
    public int getLcid() { return lcid; }
    public void setLcid(int lcid) { this.lcid = lcid; }
    public String getMetadataCacheDir() { return metadataCacheDir; }
    public void setMetadataCacheDir(String metadataCacheDir) { this.metadataCacheDir = metadataCacheDir; }
    public String getKnowledgeDir() { return knowledgeDir; }
    public void setKnowledgeDir(String knowledgeDir) { this.knowledgeDir = knowledgeDir; }
}
