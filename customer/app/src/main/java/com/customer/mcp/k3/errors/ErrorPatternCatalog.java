package com.customer.mcp.k3.errors;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.List;

/**
 * 加载金蝶错误模式库，把 K3 原始错误映射为 Agent 可执行的修正建议。
 *
 * <p>错误模式来自 k3cloud_test 项目 KnownErrors 迁移，用于消除 Agent 盲猜循环。
 * 只做包含匹配且按配置顺序取首个命中；匹配不到时透传原始错误，不伪造建议。</p>
 *
 * @author Kevin
 * @date 2026-10-16
 */
@Component
public class ErrorPatternCatalog {

    private static final String RESOURCE = "/k3/error-patterns.json";
    private static final Type LIST_TYPE = TypeToken.getParameterized(List.class, ErrorPattern.class).getType();
    private final Gson gson = new Gson();
    private List<ErrorPattern> patterns = List.of();

    /**
     * 从 ClassPath 加载错误模式；解析失败时保留空库并让调用方降级为透传原始错误。
     */
    @PostConstruct
    public void load() {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return;
            }
            List<ErrorPattern> parsed = gson.fromJson(
                    new InputStreamReader(input, StandardCharsets.UTF_8), LIST_TYPE);
            if (parsed != null) {
                patterns = List.copyOf(parsed);
            }
        } catch (IOException | RuntimeException exception) {
            // 加载失败不应阻断启动；缺失模式时执行层退回原始错误透传。
        }
    }

    /**
     * 按关键词包含匹配查找首个错误模式。
     *
     * @param rawError 金蝶原始错误描述
     * @return 首个命中的模式；未命中时返回 null
     */
    public ErrorPattern match(String rawError) {
        if (rawError == null || rawError.isBlank()) {
            return null;
        }
        return patterns.stream()
                .filter(pattern -> pattern.match() != null && !pattern.match().isBlank()
                        && rawError.contains(pattern.match()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取当前已加载的模式数量，供健康检查或测试使用。
     *
     * @return 模式数量
     */
    public int size() {
        return patterns.size();
    }
}
