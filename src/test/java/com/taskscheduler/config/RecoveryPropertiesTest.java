package com.taskscheduler.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

@DisplayName("RecoveryProperties")
class RecoveryPropertiesTest {

    @Test
    @DisplayName("should accept a valid configuration")
    void shouldAcceptValidConfiguration() {
        assertThatNoException().isThrownBy(() ->
                new RecoveryProperties(Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(2), 100));
    }

    @Test
    @DisplayName("should reject an execution timeout shorter than the Redis lock TTL")
    void shouldRejectExecutionTimeoutShorterThanLockTtl() {
        assertThatThrownBy(() ->
                new RecoveryProperties(Duration.ofSeconds(60), Duration.ofSeconds(20), Duration.ofMinutes(2), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("execution-timeout")
                .hasMessageContaining("lock TTL");
    }

    @Test
    @DisplayName("should reject an execution timeout equal to the Redis lock TTL")
    void shouldRejectExecutionTimeoutEqualToLockTtl() {
        assertThatThrownBy(() ->
                new RecoveryProperties(Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofMinutes(2), 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject a zero or negative poll interval")
    void shouldRejectNonPositivePollInterval() {
        assertThatThrownBy(() ->
                new RecoveryProperties(Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(2), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poll-interval");
    }

    @Test
    @DisplayName("should reject a zero or negative orphan threshold")
    void shouldRejectNonPositiveOrphanThreshold() {
        assertThatThrownBy(() ->
                new RecoveryProperties(Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofSeconds(-1), 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orphan-threshold");
    }

    @Test
    @DisplayName("should reject a non-positive batch size")
    void shouldRejectNonPositiveBatchSize() {
        assertThatThrownBy(() ->
                new RecoveryProperties(Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(2), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }
}
