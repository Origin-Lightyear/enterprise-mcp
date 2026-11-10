package com.customer.mcp.k3.client;

import com.kingdee.bos.webapi.entity.IdentifyInfo;
import com.kingdee.bos.webapi.sdk.K3CloudApi;
import com.kevin.mcp.agent.client.HttpAgentServerClient;
import com.kevin.mcp.agent.model.ApiCredential;
import com.kevin.mcp.execution.RequestExecutionContext;
import com.kevin.mcp.execution.RequestExecutionContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理官方金蝶 SDK 的账套连接并统一传播调用失败。
 *
 * <p>每名员工使用 Tenant 保存的来源系统凭证创建独立 SDK 会话，禁止跨员工共享登录状态，
 * 也禁止在个人凭证失败时回退到共享高权限账号。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Component
public class K3Client {

    private final K3Properties properties;
    private final RequestExecutionContextHolder executionContextHolder;
    private final HttpAgentServerClient agentServerClient;
    private final K3CloudApiFactory apiFactory;
    /** 按租户和员工隔离金蝶 SDK 会话，凭证更新时间变化后原子替换旧会话。 */
    private final ConcurrentHashMap<String, CachedClient> clients = new ConcurrentHashMap<>();

    /**
     * 注入账套连接配置。
     *
     * @param properties 金蝶数据源连接配置
     * @param executionContextHolder 当前员工执行上下文
     * @param agentServerClient Tenant 内部接口客户端
     * @param apiFactory 金蝶 SDK 客户端工厂
     */
    public K3Client(K3Properties properties,
                    RequestExecutionContextHolder executionContextHolder,
                    HttpAgentServerClient agentServerClient,
                    K3CloudApiFactory apiFactory) {
        this.properties = properties;
        this.executionContextHolder = executionContextHolder;
        this.agentServerClient = agentServerClient;
        this.apiFactory = apiFactory;
    }

    /**
     * 执行 SDK 调用，并保留原始异常供本地审计定位。
     *
     * @param operation 操作名称
     * @param call SDK 调用
     * @return 金蝶返回的 JSON
     */
    public String call(String operation, K3Call call) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("K3 connection is not configured");
        }
        try {
            return call.invoke(apiForCurrentEmployee());
        } catch (Exception exception) {
            throw new IllegalStateException("K3 " + operation + " failed", exception);
        }
    }

    private K3CloudApi apiForCurrentEmployee() {
        RequestExecutionContext context = executionContextHolder.requireContext();
        if (context.tenantId().isBlank() || context.employeeId() == null) {
            throw new SecurityException("金蝶调用缺少已验证员工身份");
        }
        ApiCredential credential = agentServerClient.fetchApiCredential(
                context.tenantId(), context.employeeId(), properties.getSourceId());
        if (!"UNIQUE_IDENTIFIER".equals(credential.credentialType())) {
            throw new SecurityException("金蝶数据源仅支持员工唯一标识");
        }
        String username = requiredCredential(credential, "identifier");
        String cacheKey = context.tenantId() + ":" + properties.getSourceId() + ":" + context.employeeId();
        CachedClient cached = clients.compute(cacheKey, (ignored, existing) -> {
            if (existing != null && Objects.equals(existing.credentialUpdatedAt(), credential.updateTime())) {
                return existing;
            }
            IdentifyInfo info = new IdentifyInfo();
            info.setServerUrl(properties.getServerUrl());
            info.setdCID(properties.getAcctId());
            info.setlCID(properties.getLcid());
            info.setUserName(username);
            info.setAppId(properties.getAppId());
            info.setAppSecret(properties.getAppSecret());
            return new CachedClient(credential.updateTime(), apiFactory.create(info));
        });
        return cached.api();
    }

    private String requiredCredential(ApiCredential credential, String field) {
        String value = credential.credential().get(field);
        if (value == null || value.isBlank()) {
            throw new SecurityException("金蝶员工凭证内容不完整");
        }
        return value;
    }

    /**
     * 保存单个员工当前凭证版本对应的金蝶 SDK 会话。
     *
     * @param credentialUpdatedAt 凭证更新时间
     * @param api 金蝶 SDK 客户端
     */
    private record CachedClient(LocalDateTime credentialUpdatedAt, K3CloudApi api) {
    }

    /**
     * 调用官方 SDK 并允许其抛出受检异常。
     *
     * @author Kevin
     * @date 2026-10-14
     */
    @FunctionalInterface
    public interface K3Call {
        /**
         * 执行单次官方 SDK 请求。
         *
         * @param api 已配置的金蝶客户端
         * @return JSON 响应
         * @throws Exception 官方 SDK 调用失败
         */
        String invoke(K3CloudApi api) throws Exception;
    }
}
