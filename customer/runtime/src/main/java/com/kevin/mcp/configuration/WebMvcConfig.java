package com.kevin.mcp.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 配置客户 Runtime 的 Web MVC 跨域规则。
 *
 * @author Kevin
 * @date 2026-07-21
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 注册 MCP HTTP 入口允许的跨域来源与请求头。
     *
     * @param registry Spring MVC 跨域注册表
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "*://*.pxbox.net", "http://10.69.188.230:*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

}
