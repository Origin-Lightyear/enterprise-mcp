package com.customer.mcp.wren.admin;

import com.customer.mcp.wren.WrenProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * 提供 Wren 管理员登录和注销入口。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@RestController
@RequestMapping("/internal/wren/admin/session")
public class WrenAdminSessionController {

    private final WrenAdminSessionService sessionService;
    private final WrenProperties properties;

    /**
     * 创建 Session Controller。
     *
     * @param sessionService Session 服务
     * @param properties Wren 管理配置
     */
    public WrenAdminSessionController(WrenAdminSessionService sessionService, WrenProperties properties) {
        this.sessionService = sessionService;
        this.properties = properties;
    }

    /**
     * 校验管理 Token 并设置 HttpOnly Session Cookie。
     *
     * @param request 登录请求
     * @param response HTTP 响应
     * @return CSRF Token 与过期秒数
     */
    @PostMapping
    public Map<String, Object> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        WrenAdminSessionService.Session session = sessionService.create(request.token());
        ResponseCookie cookie = ResponseCookie.from(WrenAdminSessionService.SESSION_COOKIE_NAME, session.id())
                .httpOnly(true)
                .secure(properties.isAdminSecureCookie())
                .sameSite("Strict")
                .path("/")
                .maxAge(properties.getAdminSessionTimeout())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return result(Map.of(
                "csrfToken", session.csrfToken(),
                "expiresInSeconds", properties.getAdminSessionTimeout().toSeconds()
        ));
    }

    /**
     * 注销当前管理 Session 并清理 Cookie。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @return 空成功响应
     */
    @DeleteMapping
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() != null) {
            Arrays.stream(request.getCookies())
                    .filter(cookie -> WrenAdminSessionService.SESSION_COOKIE_NAME.equals(cookie.getName()))
                    .map(Cookie::getValue)
                    .forEach(sessionService::invalidate);
        }
        ResponseCookie cookie = ResponseCookie.from(WrenAdminSessionService.SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(properties.isAdminSecureCookie())
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return result(Map.of());
    }

    private Map<String, Object> result(Object data) {
        return Map.of("code", "OK", "message", "OK", "data", data, "requestId", UUID.randomUUID().toString());
    }

    private record LoginRequest(String token) {
    }
}
