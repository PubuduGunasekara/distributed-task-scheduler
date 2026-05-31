package com.taskscheduler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's @Scheduled annotation processing.
 *
 * Without this, @Scheduled methods are silently ignored.
 * Kept in a dedicated class so it can be excluded in tests
 * that don't want background schedulers firing.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}