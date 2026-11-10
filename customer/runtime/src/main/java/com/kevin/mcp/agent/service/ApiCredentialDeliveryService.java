package com.kevin.mcp.agent.service;

import com.kevin.mcp.agent.model.ApiCredential;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 使用一次性请求编号接收 Tenant 反向投递的员工 API 凭证。
 *
 * @author Kevin
 * @date 2026-10-26
 */
@Service
public class ApiCredentialDeliveryService {

    private final ConcurrentHashMap<String, CompletableFuture<ApiCredential>> pendingDeliveries =
            new ConcurrentHashMap<>();
    private final Duration deliveryTimeout;

    /**
     * 配置反向凭证投递的最长等待时间。
     *
     * @param deliveryTimeout 凭证投递超时时间
     */
    public ApiCredentialDeliveryService(
            @Value("${enterprise.api-credential-delivery-timeout:10s}") Duration deliveryTimeout) {
        this.deliveryTimeout = deliveryTimeout;
    }

    /**
     * 登记一次性请求并在触发 Tenant 申请后等待固定 Runtime 地址收到凭证。
     *
     * @param requestId 不可预测的一次性请求编号
     * @param requestAction 向 Tenant 发起凭证申请的动作
     * @return Tenant 反向投递的员工凭证
     */
    public ApiCredential request(String requestId, Runnable requestAction) {
        CompletableFuture<ApiCredential> delivery = new CompletableFuture<>();
        if (pendingDeliveries.putIfAbsent(requestId, delivery) != null) {
            throw new IllegalStateException("API credential request ID already exists");
        }
        try {
            requestAction.run();
            return delivery.get(deliveryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Employee API credential delivery was interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Employee API credential delivery failed", exception);
        } finally {
            pendingDeliveries.remove(requestId, delivery);
        }
    }

    /**
     * 仅完成本机已登记的一次性请求，拒绝未知或重复投递。
     *
     * @param requestId 一次性请求编号
     * @param credential 员工 API 凭证
     */
    public void deliver(String requestId, ApiCredential credential) {
        CompletableFuture<ApiCredential> delivery = pendingDeliveries.remove(requestId);
        if (delivery == null || credential == null || !delivery.complete(credential)) {
            throw new SecurityException("API credential delivery request is unknown or expired");
        }
    }
}
