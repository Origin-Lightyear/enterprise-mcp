package com.customer.mcp.wren.admin;

import com.customer.mcp.wren.WrenSidecarClient;
import com.kevin.mcp.catalog.CatalogException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 透传 Adapter 已脱敏错误，保留前端可操作的状态码和错误原因。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@RestControllerAdvice(basePackages = "com.customer.mcp.wren.admin")
public class WrenAdminExceptionHandler {

    /**
     * 将 Catalog 业务失败映射为前端可恢复的稳定响应。
     *
     * @param exception Catalog 业务异常
     * @return 冲突或服务不可用响应
     */
    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<Map<String, Object>> handleCatalog(CatalogException exception) {
        HttpStatus status = switch (exception.code()) {
            case CONCURRENT_UPDATE, IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case NOT_READY, CONTROL_PLANE_UNAVAILABLE, REFERENCE_FINALIZATION_PENDING -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of(
                "code", exception.code().name(),
                "message", exception.getMessage(),
                "data", Map.of("error", exception.getMessage())
        ));
    }

    /**
     * 返回 Adapter 原始结构化业务错误。
     *
     * @param exception Adapter 调用异常
     * @return 同状态码 JSON 响应
     */
    @ExceptionHandler(WrenSidecarClient.WrenAdminClientException.class)
    public ResponseEntity<String> handle(WrenSidecarClient.WrenAdminClientException exception) {
        return ResponseEntity.status(exception.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(exception.getResponseBody());
    }

    /**
     * 将错误管理 Token 映射为明确的未授权响应，避免认证失败暴露为服务器异常。
     *
     * @param exception Token 校验异常
     * @return 未授权 JSON 响应
     */
    @ExceptionHandler(WrenAdminSessionService.InvalidAdminTokenException.class)
    public ResponseEntity<String> handleInvalidToken(WrenAdminSessionService.InvalidAdminTokenException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"ADMIN_TOKEN_INVALID\",\"message\":\"ADMIN_TOKEN_INVALID\",\"data\":null}");
    }
}
