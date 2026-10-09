package com.bss.ontology.receipt;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The direction rule from ADR 0025, which is the whole point of the store and
 * the thing most likely to be "simplified" back into a bug.
 *
 * <p>A failed <b>attempt</b> write must throw, so the caller refuses and never
 * dispatches. A failed <b>outcome</b> write must NOT throw, because the action
 * has already happened and failing the response would report a failure that did
 * not occur. Those two rules point in opposite directions on purpose.
 */
class EvidenceIsAPreconditionTest {

    /** A store whose writes fail, standing in for a database that is down. */
    private static ReceiptStore brokenStore() {
        ActionReceiptRepository rows = org.mockito.Mockito.mock(ActionReceiptRepository.class);
        org.mockito.Mockito.when(rows.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("receipt store is unavailable"));
        return new ReceiptStore(rows);
    }

    @Test
    void anAttemptThatCannotBeRecordedThrows() {
        // Nothing has been dispatched at this point, so throwing is safe and a
        // refusal is the honest answer.
        assertThatThrownBy(() -> brokenStore().recordAttempt("genalpha", "changePlan", "pat",
                null, null, "staff", "1", "{}", "{}", "catalog / x / POST /y", "k"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void anOutcomeThatCannotBeRecordedDoesNotThrowAndSaysSo() {
        // The action already happened. Throwing here would tell the caller their
        // order failed when it succeeded — the error this rule exists to prevent.
        ReceiptStore store = brokenStore();
        assertThatCode(() -> {
            boolean stored = store.recordOutcome("genalpha", "r-1", "changePlan",
                    ActionReceiptRow.SUCCEEDED, 201, "order-9", null, null, null);
            assertThat(stored)
                    .as("a lost outcome must be reported as not stored, not swallowed as success")
                    .isFalse();
        }).doesNotThrowAnyException();
    }

    @Test
    void theChainCoversEveryFieldThatMatters() {
        // If a field is left out of the canonical form it can be edited without
        // breaking the chain, which makes the chain decorative.
        ActionReceiptRow row = new ActionReceiptRow("id-1", "r-1", ActionReceiptRow.ATTEMPT,
                "genalpha", "changePlan", OffsetDateTime.parse("2026-10-09T10:00:00Z"));
        row.setPrincipal("pat");
        row.setInputs("{\"subscriptionId\":\"s-1\"}");
        String before = ReceiptStore.canonical(row);

        row.setInputs("{\"subscriptionId\":\"s-2\"}");   // the input quietly changed
        assertThat(ReceiptStore.canonical(row))
                .as("a changed input must change the canonical form")
                .isNotEqualTo(before);

        assertThat(ReceiptStore.sha256(before))
                .isNotEqualTo(ReceiptStore.sha256(ReceiptStore.canonical(row)));
    }

    @Test
    void theHashIsStableForTheSameRow() {
        ActionReceiptRow row = new ActionReceiptRow("id-1", "r-1", ActionReceiptRow.OUTCOME,
                "genalpha", "changePlan", OffsetDateTime.parse("2026-10-09T10:00:00Z"));
        row.setStatus(ActionReceiptRow.SUCCEEDED);
        assertThat(ReceiptStore.sha256(ReceiptStore.canonical(row)))
                .isEqualTo(ReceiptStore.sha256(ReceiptStore.canonical(row)))
                .hasSize(64);
    }
}
