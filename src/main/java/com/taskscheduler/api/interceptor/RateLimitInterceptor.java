package com.taskscheduler.api.interceptor;

import com.taskscheduler.config.RateLimitProperties;
import com.taskscheduler.domain.port.RateLimitResult;
import com.taskscheduler.domain.port.RateLimiterPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HandlerInterceptor that enforces rate limits before requests reach controllers.
 *
 * Runs in preHandle() — before any controller method executes.
 * Returning false short-circuits the request immediately.
 *
 * Client identification:
 *   Uses X-Forwarded-For header (set by load balancers/proxies) with
 *   fallback to REMOTE_ADDR. In production with API keys, you'd extract
 *   the key from Authorization header instead.
 *
 * Response headers (industry standard):
 *   X-RateLimit-Limit     — total tokens in window
 *   X-RateLimit-Remaining — tokens left after this request
 *   Retry-After           — seconds until window resets (429 only)
 *
 * 429 body uses RFC 7807 ProblemDetail format for consistency
 * with all other API error responses in this project.
 *
 * Metrics:
 *   rate_limit_allowed_total   — requests that passed
 *   rate_limit_throttled_total — requests that were rejected
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String HEADER_LIMIT     = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RETRY     = "Retry-After";

    private final RateLimiterPort      rateLimiterPort;
    private final RateLimitProperties  properties;
    private final MeterRegistry        meterRegistry;

    @Override
    public boolean preHandle(
            HttpServletRequest  request,
            HttpServletResponse response,
            Object              handler
    ) throws Exception {

        if (!properties.enabled()) {
            return true;
        }

        String          clientId = resolveClientId(request);
        RateLimitResult result   = rateLimiterPort.tryConsume(clientId);

        // Always set rate limit headers — even on denied requests
        response.setHeader(HEADER_LIMIT,     String.valueOf(result.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(result.remaining()));

        if (!result.allowed()) {
            response.setHeader(HEADER_RETRY, String.valueOf(result.windowSeconds()));

            log.warn("Rate limit exceeded: clientId={} limit={}/{}s",
                    clientId, result.limit(), result.windowSeconds());

            recordThrottled();
            writeProblemDetail(response, result);
            return false;   // ← short-circuits the request
        }

        recordAllowed();
        return true;
    }

    // =========================================================
    // PRIVATE
    // =========================================================

    private String resolveClientId(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For can be a comma-separated list; take the first
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeProblemDetail(HttpServletResponse response, RateLimitResult result)
            throws Exception {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {
                  "type": "about:blank",
                  "title": "Too Many Requests",
                  "status": 429,
                  "detail": "Rate limit of %d requests per %d seconds exceeded.",
                  "limit": %d,
                  "windowSeconds": %d
                }
                """.formatted(
                result.limit(), result.windowSeconds(),
                result.limit(), result.windowSeconds()
        ));
    }

    private void recordAllowed() {
        Counter.builder("rate_limit.requests.allowed")
                .description("Requests allowed through the rate limiter")
                .register(meterRegistry)
                .increment();
    }

    private void recordThrottled() {
        Counter.builder("rate_limit.requests.throttled")
                .description("Requests rejected by the rate limiter")
                .register(meterRegistry)
                .increment();
    }
}