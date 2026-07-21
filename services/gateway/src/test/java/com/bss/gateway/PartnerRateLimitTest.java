package com.bss.gateway;

import com.bss.gateway.ratelimit.InMemoryRateLimitStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The windows do what they say: N knocks pass, the N+1th waits, a new
 * window forgives — every partner has their OWN bucket on the strict
 * ring, the wide ring has its own independent ceiling, and the store
 * behind them is a seam (in-memory here; Redis shares the same numbers
 * across replicas, proven live by suite #57). */
class PartnerRateLimitTest {

    @Test
    void capacityAdmits_thenRefusesWithRetryAfter_perKey() throws Exception {
        PartnerRateLimitFilter filter = new PartnerRateLimitFilter(3, 300, 1200, 60000, "");
        assertThat(filter.tryAcquire("client:pos-a", 3, 300)).isZero();
        assertThat(filter.tryAcquire("client:pos-a", 3, 300)).isZero();
        assertThat(filter.tryAcquire("client:pos-a", 3, 300)).isZero();
        assertThat(filter.tryAcquire("client:pos-a", 3, 300)).isPositive();
        // another partner is untouched by A's storm
        assertThat(filter.tryAcquire("client:pos-b", 3, 300)).isZero();
        Thread.sleep(350);
        assertThat(filter.tryAcquire("client:pos-a", 3, 300)).isZero();
    }

    @Test
    void wideRing_hasItsOwnCeiling_perSubject() {
        PartnerRateLimitFilter filter = new PartnerRateLimitFilter(3, 300, 2, 60000, "");
        // the wide ring: two knocks pass, the third waits — per subject
        assertThat(filter.tryAcquire("g:sub:alice", 2, 60000)).isZero();
        assertThat(filter.tryAcquire("g:sub:alice", 2, 60000)).isZero();
        assertThat(filter.tryAcquire("g:sub:alice", 2, 60000)).isPositive();
        // bob browses on, untouched by alice's burst
        assertThat(filter.tryAcquire("g:sub:bob", 2, 60000)).isZero();
        // and the strict ring's buckets are separate rows entirely
        assertThat(filter.tryAcquire("client:pos-a", 3, 300)).isZero();
    }

    @Test
    void store_isItsOwnSeam() {
        InMemoryRateLimitStore store = new InMemoryRateLimitStore();
        assertThat(store.tryAcquire("k", 1, 60000)).isZero();
        assertThat(store.tryAcquire("k", 1, 60000)).isPositive();
    }
}
