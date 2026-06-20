package com.example.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.springboot.counter.CounterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the application context loads in the {@code test} profile.
 * Runs under Surefire as a unit test (matches the {@code *Test} pattern).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379"
})
class SpringBootApplicationTest {

    @Autowired
    private ApplicationContext context;

    // The counter service talks to Redis at startup only on first call; mocking
    // it here keeps this test purely about wiring (avoids requiring a real
    // Redis for the context-load smoke test).
    @MockBean
    private CounterService counterService;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
        assertThat(context.containsBean("counterService")).isTrue();
    }
}
