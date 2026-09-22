package com.taskscheduler.config;

import com.taskscheduler.worker.recovery.StaleTaskRecoveryScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Enables Spring's @Scheduled annotation processing.
 *
 * Without this, @Scheduled methods are silently ignored.
 * Kept in a dedicated class so it can be excluded in tests
 * that don't want background schedulers firing.
 *
 * Also registers the stale-task recovery sweep here rather than via
 * @Scheduled(fixedDelay = ...) on the scheduler itself: RetryScheduler's
 * 30s cadence is a fixed constant, but the recovery poll interval is
 * operator-tunable (RecoveryProperties), which an annotation attribute
 * can't express. SchedulingConfigurer registers it programmatically instead.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RecoveryProperties.class)
@RequiredArgsConstructor
public class SchedulerConfig implements SchedulingConfigurer {

    private final RecoveryProperties recoveryProperties;
    private final StaleTaskRecoveryScheduler staleTaskRecoveryScheduler;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(
                staleTaskRecoveryScheduler::recoverStaleTasks,
                recoveryProperties.pollInterval(),
                recoveryProperties.pollInterval()
        ));
    }
}