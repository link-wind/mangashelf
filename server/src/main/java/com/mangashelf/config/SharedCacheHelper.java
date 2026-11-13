package com.mangashelf.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 共享缓存助手：JSON 值 get/put、分钟计数限流、SETNX 短锁；所有 Redis 异常降级。 */
public class SharedCacheHelper {

    private static final Logger log = LoggerFactory.getLogger(SharedCacheHelper.class);

    private final StringRedisTemplate redis;
    private final Counter failures;

    public SharedCacheHelper(StringRedisTemplate redis, MeterRegistry registry) {
        this.redis = redis;
        this.failures = registry == null ? null
                : Counter.builder("mangashelf.redis.failures")
                        .description("Redis 读写失败次数（降级为无缓存/放行）")
                        .register(registry);
    }

    public boolean available() {
        return redis != null;
    }

    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (Exception e) {
            degrade("get " + key, e);
            return Optional.empty();
        }
    }

    public void put(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            degrade("put " + key, e);
        }
    }

    /** 分钟粒度计数 +1，返回当前计数值（供限流判断）；失败返回 -1（调用方放行）。 */
    public long incrMinute(String key, long limit) {
        try {
            String minuteKey = key + ":" + System.currentTimeMillis() / 60_000;
            Long value = redis.opsForValue().increment(minuteKey);
            if (value != null && value == 1) {
                redis.expire(minuteKey, Duration.ofSeconds(70));
            }
            return value == null ? -1 : value;
        } catch (Exception e) {
            degrade("incr " + key, e);
            return -1;
        }
    }

    /**
     * SETNX 短锁：抢到返回锁标记（释放时传入）；未抢到返回 empty。TTL 防持有者崩溃。
     */
    public Optional<String> tryLock(String key, Duration ttl) {
        try {
            String token = java.util.UUID.randomUUID().toString();
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(ok) ? Optional.of(token) : Optional.empty();
        } catch (Exception e) {
            degrade("lock " + key, e);
            return Optional.empty();
        }
    }

    public void unlock(String key, String token) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            degrade("unlock " + key, e);
        }
    }

    /** 读缓存，未命中则经 loader 回源并写回；loader 异常原样抛出。 */
    public <T> Optional<T> cached(String key, Duration ttl, Class<T> type, Supplier<T> loader) {
        Optional<String> hit = get(key);
        if (hit.isPresent()) {
            return Optional.ofNullable(decode(hit.get(), type));
        }
        T value = loader.get();
        if (value != null) {
            put(key, encode(value), ttl);
        }
        return Optional.ofNullable(value);
    }

    /** 供调用方在自管回源路径写回（值需已序列化为 JSON）。 */
    public void putJson(String key, String json, Duration ttl) {
        put(key, json, ttl);
    }

    /** 暴露 JSON 编解码（调用方自管回源路径时使用）。 */
    public String encodePublic(Object value) {
        return encode(value);
    }

    public <T> T decodePublic(String json, Class<T> type) {
        return decode(json, type);
    }

    private String encode(Object value) {
        try {
            return new tools.jackson.databind.json.JsonMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cache encode failed", e);
        }
    }

    private <T> T decode(String json, Class<T> type) {
        try {
            return new tools.jackson.databind.json.JsonMapper().readValue(json, type);
        } catch (Exception e) {
            log.warn("cache decode failed for {}, treating as miss", type.getSimpleName());
            return null;
        }
    }

    private void degrade(String op, Exception e) {
        if (failures != null) failures.increment();
        log.warn("redis degraded ({}): {}", op, e.getMessage());
    }
}
