package com.example.springboot.counter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class CounterServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    @SuppressWarnings("rawtypes")
    private ValueOperations valueOps;

    @InjectMocks
    private CounterService service;

    @Test
    void incrementReturnsValueFromRedis() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("app:counter:visits")).thenReturn(42L);

        assertThat(service.increment()).isEqualTo(42L);
    }

    @Test
    void currentParsesStoredValue() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("app:counter:visits")).thenReturn("17");

        assertThat(service.current()).isEqualTo(17L);
    }

    @Test
    void currentIsZeroWhenKeyMissing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("app:counter:visits")).thenReturn(null);

        assertThat(service.current()).isZero();
    }

    @Test
    void resetDeletesKeyAndReturnsZero() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("app:counter:visits")).thenReturn(null);

        assertThat(service.reset()).isZero();

        verify(redis).delete("app:counter:visits");
        verify(valueOps, never()).increment("app:counter:visits");
    }
}
