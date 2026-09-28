package com.taskscheduler.config;

import com.taskscheduler.worker.dispatch.DueTaskDispatchScheduler;
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
 * Also registers the dispatch and stale-task recovery sweeps here rather
 * than via @Scheduled(fixedDelay = ...) on the schedulers themselves:
 * RetryScheduler's 30s cadence is a fixed constant, but these poll
 * intervals are operator-tunable (DispatchProperties, RecoveryProperties),
 * which an annotation attribute can't express. SchedulingConfigurer
 * registers them programmatically instead.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({DispatchProperties.class, RecoveryProperties.class})
@RequiredArgsConstructor
public class SchedulerConfig implements SchedulingConfigurer {

    private final DispatchProperties dispatchProperties;
    private final DueTaskDispatchScheduler dueTaskDispatchScheduler;
    private final RecoveryProperties recoveryProperties;
    private final StaleTaskRecoveryScheduler staleTaskRecoveryScheduler;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(
                dueTaskDispatchScheduler::dispatchDueTasks,
                dispatchProperties.pollInterval(),
                dispatchProperties.pollInterval()
        ));
        registrar.addFixedDelayTask(new FixedDelayTask(
                staleTaskRecoveryScheduler::recoverStaleTasks,
                recoveryProperties.pollInterval(),
                recoveryProperties.pollInterval()
        ));
    }
}