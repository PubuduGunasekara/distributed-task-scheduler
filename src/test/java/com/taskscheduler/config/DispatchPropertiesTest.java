package com.taskscheduler.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DispatchProperties")
class DispatchPropertiesTest {

    @Test
    @DisplayName("should accept a valid configuration")
    void shouldAcceptValidConfiguration() {
        assertThatNoException().isThrownBy(() -> new DispatchProperties(Duration.ofSeconds(5), 100));
    }

    @Test
    @DisplayName("should reject a zero or negative poll interval")
    void shouldRejectNonPositivePollInterval() {
        assertThatThrownBy(() -> new DispatchProperties(Duration.ZERO, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poll-interval");
    }

    @Test
    @DisplayName("should reject a non-positive batch size")
    void shouldRejectNonPositiveBatchSize() {
        assertThatThrownBy(() -> new DispatchProperties(Duration.ofSeconds(5), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }
}
