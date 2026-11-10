package com.customer.mcp.controller;

import com.kevin.mcp.agent.model.ApiCredential;
import com.kevin.mcp.agent.service.ApiCredentialDeliveryService;
import com.kevin.mcp.security.TenantMcpAuthentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收 Tenant 向当前固定 Runtime 地址反向投递的员工 API 凭证。
 *
 * @author Kevin
 * @date 2026-10-26
 */
@RestController
@RequestMapping("/internal/tenant/api-credential-delivery")
public class TenantCredentialDeliveryController {

    /** 统一校验 Tenant 反向投递凭证时携带的实例 Token。 */
    private final TenantMcpAuthentication authentication;
    private final ApiCredentialDeliveryService deliveryService;

    /**
     * 注入 Tenant 服务认证和请求级凭证接收服务。
     *
     * @param authentication Tenant MCP 通信认证模块
     * @param deliveryService API 凭证投递服务
     */
    public TenantCredentialDeliveryController(TenantMcpAuthentication authentication,
                                              ApiCredentialDeliveryService deliveryService) {
        this.authentication = authentication;
        this.deliveryService = deliveryService;
    }

    /**
     * 校验 Tenant 身份并完成本机已登记的一次性凭证请求。
     *
     * @param suppliedToken Tenant 服务 Token
     * @param request 凭证投递请求
     */
    @PostMapping
    public void deliver(@RequestHeader(TenantMcpAuthentication.TOKEN_HEADER) String suppliedToken,
                        @RequestBody CredentialDeliveryRequest request) {
        authentication.verify(suppliedToken);
        deliveryService.deliver(request.requestId(), request.credential());
    }

    /**
     * 承载一次性请求编号与员工 API 凭证。
     *
     * @param requestId 一次性请求编号
     * @param credential 员工 API 凭证
     */
    public record CredentialDeliveryRequest(String requestId, ApiCredential credential) {
    }
}
