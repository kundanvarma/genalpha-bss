package com.bss.gateway.ratelimit;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared bucket: INCR counts the window, the first knock sets its
 * expiry, and the key's remaining TTL IS the Retry-After. Every gateway
 * replica sees the same numbers, and a restarted gateway forgets
 * nothing. FAIL-OPEN by design — rate limiting is fairness, never
 * authorization, so an unreachable Redis admits rather than outages the
 * whole API.
 */
public class RedisRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);

    private final StatefulRedisConnection<String, String> connection;

    public RedisRateLimitStore(String redisUrl) {
        this.connection = RedisClient.create(redisUrl).connect();
    }

    @Override
    public long tryAcquire(String key, int capacity, long windowMs) {
        try {
            RedisCommands<String, String> redis = connection.sync();
            String bucket = "rate:" + key;
            Long count = redis.incr(bucket);
            if (count != null && count == 1L) {
                redis.pexpire(bucket, windowMs);
            }
            if (count == null || count <= capacity) {
                return 0;
            }
            Long ttlMs = redis.pttl(bucket);
            if (ttlMs == null || ttlMs < 0) {
                // a window that lost its expiry (crash between INCR and
                // PEXPIRE) must not refuse forever — reset it
                redis.pexpire(bucket, windowMs);
                ttlMs = windowMs;
            }
            return Math.max(1, (ttlMs + 999) / 1000);
        } catch (RuntimeException redisDown) {
            log.warn("rate-limit store unreachable — admitting ({})", redisDown.getMessage());
            return 0; // fairness, not authz: fail open
        }
    }
}
