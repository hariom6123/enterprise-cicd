package com.example.springboot.counter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

/**
 * Redis-backed counter. The pipeline's IT job spins up a real
 * {@code redis:7-alpine} service and sets {@code SPRING_REDIS_HOST=localhost},
 * so this connects through Spring Boot's auto-configured
 * {@link StringRedisTemplate}.
 */
@Service
public class CounterService {

    private static final String KEY = "app:counter:visits";

    private final StringRedisTemplate redis;
    private final ValueOperations<String, String> ops;

    public CounterService(StringRedisTemplate redis) {
        this.redis = redis;
        // Cache the ValueOperations proxy once — the lookup is cheap but
        // called on every read/write of a counter that's polled.
        this.ops = redis.opsForValue();
    }

    public long increment() {
        Long value = ops.increment(KEY);
        return value == null ? 0L : value;
    }

    public long current() {
        String value = ops.get(KEY);
        return value == null ? 0L : Long.parseLong(value);
    }

    public long reset() {
        redis.delete(KEY);
        return current();
    }
}
