package com.bss.ontology.service;

import com.bss.ontology.receipt.ActionReceiptRepository;
import com.bss.ontology.receipt.ActionReceiptRow;
import com.bss.ontology.receipt.ReceiptStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * A machine's value budget is per target, not per request (#422). An AI worker
 * reads ticket text it did not write; talked into crediting the same bill again
 * and again just under the approval line, it gets one threshold per bill per
 * month — the next one goes to a person. People, and approvers executing a filed
 * request, are not counted.
 */
class MachineValueBudgetTest {

    private static final JsonNode CREDIT = new ObjectMapper().readTree("""
            {"action": "issueCredit",
             "inputs": [{"name": "billId", "type": "ref"}, {"name": "amount", "type": "money"}],
             "governance": {"approval": "human", "approvalAbove": {"input": "amount", "amount": 25}}}
            """);

    private final List<ActionReceiptRow> rows = new ArrayList<>();
    private final ReceiptStore store;

    MachineValueBudgetTest() {
        ActionReceiptRepository repo = mock(ActionReceiptRepository.class);
        given(repo.findByTenantIdAndActionAndPhaseAndRecordedAtAfter(eq("genalpha"), eq("issueCredit"),
                eq(ActionReceiptRow.ATTEMPT), any())).willAnswer(i -> rows);
        store = new ReceiptStore(repo);
    }

    private static Caller worker() {
        return new Caller("t", "bss-worker", Set.of("agent", "workforce:use"), "console", "genalpha", null);
    }

    private static Caller person() {
        return new Caller("t", "anna", Set.of("agent"), "console", "genalpha", null);
    }

    private void alreadyCredited(String bill, String amount, String agent, String authority) {
        ActionReceiptRow r = new ActionReceiptRow("id-" + rows.size(), "r-" + rows.size(), ActionReceiptRow.ATTEMPT,
                "genalpha", "issueCredit", OffsetDateTime.now().minusDays(2));
        r.setInputs("{\"billId\":\"" + bill + "\",\"amount\":\"" + amount + "\"}");
        r.setAgent(agent);
        r.setAuthority(authority);
        rows.add(r);
    }

    private boolean needsHuman(Caller caller, String bill, String amount) {
        return ActionExecuteService.valueNeedsHuman(CREDIT, Map.of("billId", bill, "amount", amount),
                caller, store, "issueCredit");
    }

    @Test
    void aWorkerUnderTheLineActsOnce_butNotTwiceOnTheSameBill() {
        assertThat(needsHuman(worker(), "bill-1", "20")).as("first small credit: alone").isFalse();
        alreadyCredited("bill-1", "20", null, "agent,workforce:use");
        assertThat(needsHuman(worker(), "bill-1", "20")).as("again on the same bill: a person decides").isTrue();
        assertThat(needsHuman(worker(), "bill-2", "20")).as("another bill has its own budget").isFalse();
    }

    @Test
    void peopleAndApproversAreNotCounted_andAboveTheLineIsAlwaysAPerson() {
        alreadyCredited("bill-1", "20", null, "billing:admin");   // an approver executed a filed request
        assertThat(needsHuman(worker(), "bill-1", "5")).isFalse();
        assertThat(needsHuman(person(), "bill-1", "25")).isFalse();
        assertThat(needsHuman(person(), "bill-1", "26")).isTrue();
        assertThat(needsHuman(worker(), "bill-9", "26")).isTrue();
    }
}
