package com.kevin.mcp.cloud.api;

import java.time.Instant;

/**
 * 返回稳定的 Control Plane API 错误结构，避免向调用方暴露内部异常栈和存储细节。
 *
 * @param timestamp 错误发生时间
 * @param status HTTP 状态码
 * @param code 稳定错误代码
 * @param message 可供调用方定位请求问题的安全说明
 * @author Kevin
 * @date 2026-09-10
 */
public record ControlPlaneErrorResponse(Instant timestamp, int status, String code, String message) {
}
