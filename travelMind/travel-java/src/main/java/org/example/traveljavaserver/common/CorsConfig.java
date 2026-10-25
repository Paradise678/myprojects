package org.example.traveljavaserver.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 全局跨域（CORS）配置
 *
 * 前端运行在 Vite 开发服务器（如 localhost:5173），后端在 localhost:3200，
 * 端口不同即跨域。浏览器会拦截跨域请求，因此需要后端显式允许跨域访问。
 *
 * 这里配置为允许所有来源、所有请求头、所有方法，方便开发调试。
 * 生产环境建议收紧 allowedOriginPatterns，只允许可信域名。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);                       // 允许携带 Cookie 等凭证
        config.addAllowedOriginPattern("*");                    // 允许所有来源
        config.addAllowedHeader("*");                           // 允许所有请求头
        config.addAllowedMethod("*");                           // 允许所有 HTTP 方法（GET/POST/...）
        config.addExposedHeader("*");                           // 允许前端读取所有响应头

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);        // 对所有路径生效
        return new CorsFilter(source);
    }
}
