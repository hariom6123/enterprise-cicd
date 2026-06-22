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

    public CounterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // Look up ValueOperations lazily on every call rather than caching it
    // in the constructor. Reasons:
    //   1. Lets unit tests stub redis.opsForValue() before any call site
    //      uses it — @InjectMocks would otherwise instantiate the service
    //      with a null proxy.
    //   2. The ValueOperations proxy is internally tied to the
    //      StringRedisTemplate's connection factory; caching it across
    //      reconnects (e.g. after a Redis failover) can return a stale
    //      proxy. The lookup is cheap.
    private ValueOperations<String, String> ops() {
        return redis.opsForValue();
    }

    public long increment() {
        Long value = ops().increment(KEY);
        return value == null ? 0L : value;
    }

    public long current() {
        String value = ops().get(KEY);
        return value == null ? 0L : Long.parseLong(value);
    }

    public long reset() {
        redis.delete(KEY);
        return current();
    }
}
