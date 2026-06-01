package com.taskscheduler.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that all required environment variables are present
 * before the application finishes starting.
 *
 * Runs only in the prod profile — dev uses defaults from application.yml.
 *
 * Without this, a missing DB_HOST silently passes through Spring's
 * property resolution and surfaces as an UnknownHostException deep
 * in the PostgreSQL driver stack — unhelpful for operations teams.
 *
 * With this, the failure is immediate and explicit:
 * "FATAL: Missing required environment variables: [DB_HOST, DB_PASSWORD]"
 */
@Slf4j
@Component
@Profile("prod")
public class ProductionConfigValidator {

    private static final List<String> REQUIRED_VARS = List.of(
            "DB_HOST",
            "DB_NAME",
            "DB_USER",
            "DB_PASSWORD",
            "REDIS_HOST",
            "KAFKA_BOOTSTRAP_SERVERS"
    );

    @PostConstruct
    public void validate() {
        List<String> missing = new ArrayList<>();

        for (String var : REQUIRED_VARS) {
            String value = System.getenv(var);
            if (value == null || value.isBlank()) {
                missing.add(var);
            }
        }

        if (!missing.isEmpty()) {
            log.error("═══════════════════════════════════════════════════");
            log.error("FATAL: Missing required environment variables:");
            missing.forEach(var -> log.error("  ✗ {}", var));
            log.error("See .env.example for the full list of required vars.");
            log.error("═══════════════════════════════════════════════════");
            throw new IllegalStateException(
                    "Application cannot start: missing required environment variables: " + missing
            );
        }

        log.info("Production config validated — all required environment variables present");
    }
}