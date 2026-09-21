package com.customer.mcp.wren.admin;

import com.customer.mcp.wren.WrenProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;

/**
 * 保护 Wren 管理 API，并对所有修改请求执行 CSRF 校验。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@Component
public class WrenAdminAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/internal/wren/admin";
    private static final String UI_PREFIX = "/admin/wren";
    private static final String SESSION_PATH = API_PREFIX + "/session";
    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final WrenAdminSessionService sessionService;
    private final WrenProperties properties;

    /**
     * 创建管理鉴权过滤器。
     *
     * @param sessionService Session 服务
     * @param properties Wren 管理配置
     */
    public WrenAdminAuthenticationFilter(WrenAdminSessionService sessionService, WrenProperties properties) {
        this.sessionService = sessionService;
        this.properties = properties;
    }

    /**
     * 校验管理入口启用状态、Session 与 CSRF Token。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 过滤器链执行失败
     * @throws IOException 响应写入失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean adminApi = path.startsWith(API_PREFIX);
        boolean adminUi = path.startsWith(UI_PREFIX);
        if (!adminApi && !adminUi) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!sessionService.isEnabled()) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "WREN_ADMIN_DISABLED");
            return;
        }
        if (SESSION_PATH.equals(path) && "POST".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        // SPA 静态资源只承载登录壳，不返回任何管理数据。
        if (adminUi && "GET".equals(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String sessionId = readCookie(request, WrenAdminSessionService.SESSION_COOKIE_NAME).orElse(null);
        Optional<WrenAdminSessionService.Session> session = sessionService.touch(sessionId);
        if (session.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_SESSION_REQUIRED");
            return;
        }
        if (isMutation(request.getMethod())) {
            String suppliedCsrf = request.getHeader(CSRF_HEADER);
            if (suppliedCsrf == null || !MessageDigest.isEqual(
                    suppliedCsrf.getBytes(StandardCharsets.UTF_8),
                    session.get().csrfToken().getBytes(StandardCharsets.UTF_8))) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "CSRF_TOKEN_INVALID");
                return;
            }
        }
        request.setAttribute(WrenAdminSessionService.Session.class.getName(), session.get());
        filterChain.doFilter(request, response);
    }

    private boolean isMutation(String method) {
        return !"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method);
    }

    private Optional<String> readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private void writeError(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + code + "\",\"data\":null}");
    }
}
