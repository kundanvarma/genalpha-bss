package com.bss.ordering.client;

import com.bss.ordering.client.RestCollectionsClient.Amount;
import com.bss.ordering.client.RestCollectionsClient.CollectionCase;
import com.bss.ordering.client.RestCollectionsClient.Holds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "In collections" for Benefit Choices eligibility (#391, review): only a case on
 * the ladder that nothing pauses. A promise to pay, a disputed amount and
 * hardship each pause it — such a household is not blocked from new picks.
 */
class CollectionsHoldsTest {

    private static final Amount SOME = new Amount(new BigDecimal("120.00"));

    @Test
    void anOpenCaseWithNothingPausingIt_isInCollections() {
        assertThat(RestCollectionsClient.overdue(new CollectionCase("suspended", null))).isTrue();
        assertThat(RestCollectionsClient.overdue(new CollectionCase("warned", new Holds(null, null, false)))).isTrue();
    }

    @Test
    void aPromiseADisputeOrHardship_eachPausesIt() {
        assertThat(RestCollectionsClient.overdue(new CollectionCase("warned", new Holds(SOME, null, null)))).isFalse();
        assertThat(RestCollectionsClient.overdue(new CollectionCase("warned", new Holds(null, SOME, null)))).isFalse();
        assertThat(RestCollectionsClient.overdue(new CollectionCase("restricted", new Holds(null, null, true)))).isFalse();
    }

    @Test
    void aCaseOffTheLadder_isNotInCollections() {
        assertThat(RestCollectionsClient.overdue(new CollectionCase("current", null))).isFalse();
        assertThat(RestCollectionsClient.overdue(new CollectionCase("writtenOff", null))).isFalse();
        assertThat(RestCollectionsClient.overdue(null)).isFalse();
    }
}
