package com.bss.policy.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A rule's message may name the facts it was decided on (#356) — plain values only, never a structure. */
class DecisionMessageTest {

    private static PolicyService.Decision deny(String message) {
        return new PolicyService.Decision(false, "r1", "Choices: 3 swaps a quarter", message);
    }

    @Test
    void aNamedFactIsFilledIn() {
        assertThat(deny("You have made {choiceSwapsThisQuarter} swaps — the next is possible from {nextQuarterStarts}.")
                .filled(Map.of("choiceSwapsThisQuarter", 3, "nextQuarterStarts", "2027-01-01")).message())
                .isEqualTo("You have made 3 swaps — the next is possible from 2027-01-01.");
    }

    @Test
    void aMissingFactOrAStructureIsLeftAsWritten() {
        assertThat(deny("Next from {nextQuarterStarts}; items {items}; {notThere}.")
                .filled(Map.of("items", List.of(Map.of("offeringId", "x")))).message())
                .isEqualTo("Next from {nextQuarterStarts}; items {items}; {notThere}.");
    }

    @Test
    void aDollarSignInAValueIsTakenLiterally() {
        assertThat(deny("Price {p}").filled(Map.of("p", "$1 and \\\\0")).message()).isEqualTo("Price $1 and \\\\0");
    }
}
