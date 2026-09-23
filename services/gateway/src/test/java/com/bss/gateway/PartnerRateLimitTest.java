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
        PartnerRateLimitFilter filter = new PartnerRateLimitFilter(3, 300, 1200, 60000, 30, 60000, "/probe", "");
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
        PartnerRateLimitFilter filter = new PartnerRateLimitFilter(3, 300, 2, 60000, 30, 60000, "/probe", "");
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

    @Test
    void theWalkableLookupHasItsOwnTightCeiling() {
        // The number offer must stay anonymous — a shopper picks a number
        // before signing in — but every draw also says which candidates are
        // NOT free, so a caller varying the shuffle can map an operator's
        // issued numbers. The wide ring's 1200/min never notices that; this
        // one is sized for a human pressing shuffle.
        PartnerRateLimitFilter filter =
                new PartnerRateLimitFilter(3, 300, 1200, 60000, 4, 60000,
                        "/tmf-api/resourcePoolManagement/v4/numberOffer", "");

        String key = "p:/tmf-api/resourcePoolManagement/v4/numberOffer:ip:1.2.3.4";
        assertThat(filter.tryAcquire(key, 4, 60000)).isZero();
        assertThat(filter.tryAcquire(key, 4, 60000)).isZero();
        assertThat(filter.tryAcquire(key, 4, 60000)).isZero();
        assertThat(filter.tryAcquire(key, 4, 60000)).isZero();
        // the fifth draw in the window waits
        assertThat(filter.tryAcquire(key, 4, 60000)).isPositive();

        // and it is per caller: another shopper is unaffected
        assertThat(filter.tryAcquire(
                "p:/tmf-api/resourcePoolManagement/v4/numberOffer:ip:5.6.7.8", 4, 60000)).isZero();
        // while ordinary browsing keeps the wide ring's room
        assertThat(filter.tryAcquire("g:ip:1.2.3.4", 1200, 60000)).isZero();
    }
}
