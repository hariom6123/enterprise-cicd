package com.example.springboot.counter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full-stack integration test for the Redis counter.
 * <p>
 * Runs under Failsafe ({@code *IT}). The pipeline's IT job sets
 * {@code SPRING_REDIS_HOST=localhost} pointing at the real
 * {@code redis:7-alpine} service container, which auto-enables this test.
 * On a developer machine without Redis configured, the test is skipped —
 * the unit test in {@code CounterServiceTest} covers the logic.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "SPRING_REDIS_HOST", matches = ".+")
class CounterControllerIT {

    @Autowired
    private CounterController controller;

    @Test
    void incrementAndRead() {
        // Reset to a known state — the previous test (or a previous run) may
        // have left a value in Redis.
        assertThat(controller.reset().count()).isZero();

        assertThat(controller.get().count()).isZero();
        assertThat(controller.increment().count()).isEqualTo(1L);
        assertThat(controller.increment().count()).isEqualTo(2L);
        assertThat(controller.get().count()).isEqualTo(2L);

        assertThat(controller.reset().count()).isZero();
    }
}
