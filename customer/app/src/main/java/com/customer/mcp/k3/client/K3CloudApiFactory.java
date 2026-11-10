package com.customer.mcp.k3.client;

import com.kingdee.bos.webapi.entity.IdentifyInfo;
import com.kingdee.bos.webapi.sdk.K3CloudApi;
import org.springframework.stereotype.Component;

/**
 * 创建绑定单个员工来源系统身份的金蝶 SDK 客户端。
 *
 * <p>把第三方 SDK 构造收口为独立接口，使会话隔离和凭证轮换可以在不访问金蝶的情况下验证。</p>
 *
 * @author Kevin
 * @date 2026-10-26
 */
@Component
public class K3CloudApiFactory {

    /**
     * 使用完整金蝶身份信息创建 SDK 客户端。
     *
     * @param identifyInfo 当前员工的金蝶连接身份
     * @return 独立 SDK 客户端
     */
    public K3CloudApi create(IdentifyInfo identifyInfo) {
        return new K3CloudApi(identifyInfo);
    }
}
