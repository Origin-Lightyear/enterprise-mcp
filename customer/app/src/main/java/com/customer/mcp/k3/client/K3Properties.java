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

    /** 金蝶 API 数据源稳定标识，修改后既有员工凭证绑定将不再匹配。 */
    private String sourceId = "kingdee-k3";
    /** 金蝶 API 数据源显示名称。 */
    private String displayName = "金蝶云星空";

    /** 显式启用金蝶能力，避免未配置客户看到不可用工具。 */
    private boolean enabled;

    /** 金蝶 WebAPI 根地址。 */
    private String serverUrl = "";
    /** 金蝶账套 ID。 */
    private String acctId = "";
    /** 金蝶数据源级应用编号，不属于员工身份。 */
    private String appId = "";
    /** 金蝶数据源级应用密钥，不属于员工身份。 */
    private String appSecret = "";
    /** 金蝶语言 ID。 */
    private int lcid = 2052;
    /** 本地元数据缓存目录。 */
    private String metadataCacheDir = "./data/k3/metadata-cache";
    /** 本地知识目录。 */
    private String knowledgeDir = "./data/k3/learned";

    public boolean isConfigured() {
        return !sourceId.isBlank() && !serverUrl.isBlank() && !acctId.isBlank()
                && !appId.isBlank() && !appSecret.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId == null ? "" : sourceId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName == null ? "" : displayName; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl == null ? "" : serverUrl; }
    public String getAcctId() { return acctId; }
    public void setAcctId(String acctId) { this.acctId = acctId == null ? "" : acctId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId == null ? "" : appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret == null ? "" : appSecret; }
    public int getLcid() { return lcid; }
    public void setLcid(int lcid) { this.lcid = lcid; }
    public String getMetadataCacheDir() { return metadataCacheDir; }
    public void setMetadataCacheDir(String metadataCacheDir) { this.metadataCacheDir = metadataCacheDir; }
    public String getKnowledgeDir() { return knowledgeDir; }
    public void setKnowledgeDir(String knowledgeDir) { this.knowledgeDir = knowledgeDir; }
}
