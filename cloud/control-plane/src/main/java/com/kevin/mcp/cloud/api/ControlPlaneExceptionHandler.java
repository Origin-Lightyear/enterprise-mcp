package com.kevin.mcp.cloud.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 转换 Control Plane 业务异常为稳定 HTTP 状态，隐藏 JDBC 和签名实现细节。
 *
 * @author Kevin
 * @date 2026-09-10
 */
@RestControllerAdvice
public class ControlPlaneExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneExceptionHandler.class);

    /**
     * 返回请求参数错误。
     *
     * @param exception 参数异常
     * @return HTTP 400 错误
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ControlPlaneErrorResponse> handleBadRequest(IllegalArgumentException exception) {
        log.warn("Control Plane rejected invalid request: code=INVALID_REQUEST, reason={}", exception.getMessage());
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    /**
     * 返回目录版本或状态并发冲突。
     *
     * @param exception 状态异常
     * @return HTTP 409 错误
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ControlPlaneErrorResponse> handleConflict(IllegalStateException exception) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", exception.getMessage());
    }

    /**
     * 返回客户、Runtime 或计划绑定拒绝结果。
     *
     * @param exception 安全异常
     * @return HTTP 403 错误
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ControlPlaneErrorResponse> handleForbidden(SecurityException exception) {
        return response(HttpStatus.FORBIDDEN, "BINDING_REJECTED", exception.getMessage());
    }

    /**
     * 记录未预期异常并返回固定说明，避免数据库或密钥信息进入响应体。
     *
     * @param exception 未预期异常
     * @return HTTP 500 错误
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ControlPlaneErrorResponse> handleInternalError(Exception exception) {
        log.error("Unhandled Control Plane request failure", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Control Plane request failed");
    }

    private ResponseEntity<ControlPlaneErrorResponse> response(HttpStatus status, String code, String message) {
        ControlPlaneErrorResponse body = new ControlPlaneErrorResponse(
                Instant.now(), status.value(), code, message == null ? status.getReasonPhrase() : message);
        return ResponseEntity.status(status).body(body);
    }
}
