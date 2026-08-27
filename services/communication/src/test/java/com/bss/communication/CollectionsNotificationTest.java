package com.bss.communication;

import com.bss.communication.notify.EventNotificationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The collections events speak to the customer — with the legally required
 * words on the warning rung, and nothing at all on a write-off. */
class CollectionsNotificationTest {

    private final EventNotificationMapper mapper = new EventNotificationMapper();

    private Map<String, Object> caseResource(Map<String, Object> step) {
        java.util.Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("accountId", "cust-1");
        c.put("overdueBalance", Map.of("unit", "NOK", "value", 437.0));
        c.put("billNo", "BILL-202608-XYZ");
        c.put("relatedParty", List.of(Map.of("id", "cust-1", "role", "customer")));
        if (step != null) {
            c.put("step", step);
        }
        return c;
    }

    @Test
    void dunningSteps_remindAndWarn_speak_enforcementRungsStaySilent() {
        var remind = mapper.map("DunningStepReachedEvent", Map.of("collectionCase",
                caseResource(Map.of("action", "remind", "feeCharged", 38))));
        assertThat(remind).hasSize(1);
        assertThat(remind.get(0).partyId()).isEqualTo("cust-1");
        assertThat(remind.get(0).subject()).isEqualTo("Payment reminder");
        assertThat(remind.get(0).content()).contains("437.0 NOK").contains("reminder fee of 38");

        var warn = mapper.map("DunningStepReachedEvent", Map.of("collectionCase",
                caseResource(Map.of("action", "warn", "enforceableAt", "2026-09-27T00:00:00Z"))));
        assertThat(warn).hasSize(1);
        assertThat(warn.get(0).content())
                .contains("restricted or suspended at the earliest on 2026-09-27")
                .contains("Emergency numbers always stay reachable");

        // restrict/suspend rungs notify through their dedicated events — the
        // step event stays silent so nobody is told twice
        assertThat(mapper.map("DunningStepReachedEvent", Map.of("collectionCase",
                caseResource(Map.of("action", "suspend"))))).isEmpty();
    }

    @Test
    void enforcementCureAndPromises_haveTheirOwnWords() {
        assertThat(mapper.map("ServiceRestrictedForNonPaymentEvent",
                Map.of("collectionCase", caseResource(null))).get(0).content())
                .contains("Emergency numbers still work");
        assertThat(mapper.map("ServiceSuspendedForNonPaymentEvent",
                Map.of("collectionCase", caseResource(null))).get(0).content())
                .contains("No subscription charges accrue while it is suspended");
        assertThat(mapper.map("CollectionCuredEvent",
                Map.of("collectionCase", caseResource(null))).get(0).subject())
                .contains("services are restored");

        Map<String, Object> promise = Map.of(
                "amount", 437.0, "currency", "NOK", "dueAt", "2026-09-05T12:00:00Z",
                "relatedParty", List.of(Map.of("id", "cust-1", "role", "customer")));
        assertThat(mapper.map("PromiseToPayCreatedEvent", Map.of("promiseToPay", promise))
                .get(0).content()).contains("437.0 NOK by 2026-09-05");
        assertThat(mapper.map("PromiseToPayBrokenEvent", Map.of("promiseToPay", promise))
                .get(0).subject()).isEqualTo("Missed payment promise");
        assertThat(mapper.map("PromiseToPayKeptEvent", Map.of("promiseToPay", promise))
                .get(0).subject()).contains("Promise kept");

        // a write-off is an internal ledger event, never a customer letter
        assertThat(mapper.map("DebtWrittenOffEvent",
                Map.of("collectionCase", caseResource(null)))).isEmpty();
    }
}
