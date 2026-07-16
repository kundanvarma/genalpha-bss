package com.bss.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The window does what it says: N knocks pass, the N+1th waits, a new
 * window forgives — and every partner has their OWN bucket. */
class PartnerRateLimitTest {

    @Test
    void capacityAdmits_thenRefusesWithRetryAfter_perKey() throws Exception {
        PartnerRateLimitFilter filter = new PartnerRateLimitFilter(3, 300);
        assertThat(filter.tryAcquire("client:pos-a")).isZero();
        assertThat(filter.tryAcquire("client:pos-a")).isZero();
        assertThat(filter.tryAcquire("client:pos-a")).isZero();
        assertThat(filter.tryAcquire("client:pos-a")).isPositive();
        // another partner is untouched by A's storm
        assertThat(filter.tryAcquire("client:pos-b")).isZero();
        Thread.sleep(350);
        assertThat(filter.tryAcquire("client:pos-a")).isZero();
    }
}
