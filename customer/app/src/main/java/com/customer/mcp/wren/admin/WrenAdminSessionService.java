package com.customer.mcp.wren.admin;

import com.customer.mcp.wren.WrenProperties;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 校验管理 Token 并维护短期内存 Session，避免长期 Token 被浏览器持续携带。
 *
 * @author Kevin
 * @date 2026-09-17
 */
@Service
public class WrenAdminSessionService {

    /** 管理 Session Cookie 名称。 */
    public static final String SESSION_COOKIE_NAME = "WREN_ADMIN_SESSION";

    private static final int RANDOM_BYTES = 32;

    private final WrenProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 创建 Session 服务。
     *
     * @param properties Wren 管理配置
     */
    @Autowired
    public WrenAdminSessionService(WrenProperties properties) {
        this(properties, Clock.systemUTC(), new SecureRandom());
    }

    WrenAdminSessionService(WrenProperties properties, Clock clock, SecureRandom secureRandom) {
        this.properties = properties;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    /**
     * 判断管理入口是否启用。
     *
     * @return 配置有效管理 Token 时返回 true
     */
    public boolean isEnabled() {
        return properties.getAdminToken() != null && !properties.getAdminToken().isBlank();
    }

    /**
     * 校验共享 Token 并签发随机 Session 与 CSRF Token。
     *
     * @param suppliedToken 请求提供的管理 Token
     * @return 新 Session
     * @throws InvalidAdminTokenException Token 错误或管理入口未启用
     */
    public Session create(String suppliedToken) {
        if (!isEnabled() || suppliedToken == null
                || !MessageDigest.isEqual(digest(properties.getAdminToken()), digest(suppliedToken))) {
            throw new InvalidAdminTokenException();
        }
        Instant now = clock.instant();
        Session session = new Session(randomToken(), randomToken(), now, now);
        sessions.put(session.id(), session);
        return session;
    }

    /**
     * 读取有效 Session 并刷新最后访问时间。
     *
     * @param sessionId Cookie 中的 Session ID
     * @return 有效 Session；不存在或过期时返回空
     */
    public Optional<Session> touch(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Session current = sessions.get(sessionId);
        if (current == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (current.lastAccessAt().plus(properties.getAdminSessionTimeout()).isBefore(now)) {
            sessions.remove(sessionId, current);
            return Optional.empty();
        }
        Session refreshed = new Session(current.id(), current.csrfToken(), current.createdAt(), now);
        sessions.replace(sessionId, current, refreshed);
        return Optional.of(refreshed);
    }

    /**
     * 注销指定 Session。
     *
     * @param sessionId Session ID
     */
    public void invalidate(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 表示一次管理员浏览器会话。
     *
     * @param id 随机 Session ID
     * @param csrfToken 修改请求必须携带的 CSRF Token
     * @param createdAt 创建时间
     * @param lastAccessAt 最后访问时间
     */
    public record Session(String id, String csrfToken, Instant createdAt, Instant lastAccessAt) {
    }

    /** 表示管理 Token 校验失败。 */
    public static class InvalidAdminTokenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** 创建不携带敏感输入的认证异常。 */
        public InvalidAdminTokenException() {
            super("ADMIN_TOKEN_INVALID");
        }
    }
}
