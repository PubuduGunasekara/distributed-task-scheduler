package com.taskscheduler.config;

import com.taskscheduler.api.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the rate limit interceptor on task submission endpoints.
 *
 * Path pattern choices:
 *   /api/v1/tasks  — rate-limits task creation (POST)
 *   Actuator endpoints excluded — health/metrics must always be reachable
 *   GET endpoints excluded — read operations are cheap, write operations
 *   (task submission) are what need protection from abuse
 *
 * In production you'd typically rate-limit all /api/** paths with
 * different limits per tier (free vs paid vs internal).
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@RequiredArgsConstructor
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/tasks")
                .addPathPatterns("/api/v1/tasks/**");
    }
}