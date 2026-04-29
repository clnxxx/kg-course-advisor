package com.clnx.kg_course_advisor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 字符编码配置
 * 确保所有请求和响应都使用 UTF-8 编码
 */
@Configuration
public class EncodingConfig {

    @Bean
    public OncePerRequestFilter characterEncodingFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, 
                    FilterChain filterChain) throws ServletException, IOException {
                request.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                filterChain.doFilter(request, response);
            }
        };
    }
}
