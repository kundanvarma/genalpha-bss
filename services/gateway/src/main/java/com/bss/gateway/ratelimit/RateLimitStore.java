package com.bss.gateway.ratelimit;

/**
 * WHERE THE BUCKETS LIVE is a seam: in-memory for a single gateway (dev
 * default, zero infrastructure), Redis when replicas must share one
 * ceiling — N gateways with in-memory buckets means N× the dial; shared
 * buckets keep the ceiling exact, and survive a restart.
 */
public interface RateLimitStore {

    /** 0 = admitted; otherwise seconds until the caller's window resets. */
    long tryAcquire(String key, int capacity, long windowMs);
}
