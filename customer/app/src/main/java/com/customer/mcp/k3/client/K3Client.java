package com.customer.mcp.k3.client;

import com.kingdee.bos.webapi.entity.IdentifyInfo;
import com.kingdee.bos.webapi.sdk.K3CloudApi;
import org.springframework.stereotype.Component;

/**
 * 管理官方金蝶 SDK 的账套连接并统一传播调用失败。
 *
 * <p>沿用源项目的单例客户端：SDK 管理登录会话及失效重登，本类不在每次请求前自行登录。
 * 客户账套配置和凭证仅留在 Runtime，本类异常不应把密码或 AppSecret 返回给 Agent。</p>
 *
 * @author Kevin
 * @date 2026-10-14
 */
@Component
public class K3Client {

    private final K3Properties properties;
    /** 延迟构造并用双重检查锁保证 SDK 客户端只初始化一次。 */
    private volatile K3CloudApi api;

    /**
     * 注入账套连接配置。
     *
     * @param properties 客户本地配置
     */
    public K3Client(K3Properties properties) {
        this.properties = properties;
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
            return call.invoke(api());
        } catch (Exception exception) {
            throw new IllegalStateException("K3 " + operation + " failed", exception);
        }
    }

    private K3CloudApi api() {
        if (api == null) {
            synchronized (this) {
                if (api == null) {
                    IdentifyInfo info = new IdentifyInfo();
                    info.setServerUrl(properties.getServerUrl());
                    info.setdCID(properties.getAcctId());
                    info.setlCID(properties.getLcid());
                    info.setUserName(properties.getUsername());
                    if ("password".equalsIgnoreCase(properties.getAuthMode())) {
                        // 源项目使用 ValidateUser：以真实登录用户身份调用，金蝶侧仍执行其数据权限。
                        info.setPwd(properties.getPassword());
                    } else {
                        // 源项目使用 LoginByAppSecret；这与账号密码模式的金蝶侧身份语义不同。
                        info.setAppId(properties.getAppId());
                        info.setAppSecret(properties.getAppSecret());
                    }
                    api = new K3CloudApi(info);
                }
            }
        }
        return api;
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
