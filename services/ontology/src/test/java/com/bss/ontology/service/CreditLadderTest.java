package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.dto.Check;
import com.bss.ontology.dto.PermissionVerdict;
import com.bss.ontology.dto.PolicyVerdict;
import com.bss.ontology.receipt.ActionReceiptRepository;
import com.bss.ontology.receipt.ActionReceiptRow;
import com.bss.ontology.receipt.ReceiptStore;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.tracing.ActionTracing;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The value ladder through the whole execute path (review findings 8 and 17):
 * an approver's token driven by a machine still meets the machine's budget per
 * target — only an approver PERSON is exempt — and a request the budget files
 * tells the approver the real reason, not "above the threshold" when the single
 * amount was under it.
 */
class CreditLadderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JsonNode CREDIT = JSON.readTree("""
            {"action":"issueCredit","concept":"Bill","version":1,
             "inputs":[{"name":"billId","type":"ref","required":true},
                       {"name":"amount","type":"money","required":true},
                       {"name":"reason","type":"string","required":true}],
             "governance":{"autonomy":"medium","approval":"human",
                           "approvalAbove":{"input":"amount","amount":25},
                           "approverRole":"billing:admin"},
             "executes":{"capability":"billing.creditNote","as":"registry",
                         "pathVars":{"id":"${billId}"},
                         "template":{"amount":"${amount}","reason":"${reason}"}}}""");

    private final ActionReceiptRepository rows = mock(ActionReceiptRepository.class);
    private final ComponentClient client = mock(ComponentClient.class);
    private final ActionCheckService checks = mock(ActionCheckService.class);
    private final List<ActionReceiptRow> history = new ArrayList<>();
    private ActionExecuteService service;

    @BeforeEach
    void wire() {
        Map<String, JsonNode> caps = new TreeMap<>();
        caps.put("billing.creditNote", JSON.readTree("""
                {"id":"billing.creditNote","component":"billing",
                 "route":{"method":"POST","path":"/bill/{id}/creditNote"}}"""));
        caps.put("workforce.fileApproval", JSON.readTree("""
                {"id":"workforce.fileApproval","component":"workforce",
                 "route":{"method":"POST","path":"/workforce/approval"}}"""));
        Registry registry = mock(Registry.class);
        when(registry.forTenant(anyString())).thenReturn(new Registry.Layer(new TreeMap<>(), CREDIT_ACTIONS(),
                caps, new TreeMap<>(), new TreeMap<>()));
        when(rows.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(rows.findByTenantIdAndActionAndPhaseAndRecordedAtAfter(eq("genalpha"), eq("issueCredit"),
                eq(ActionReceiptRow.ATTEMPT), any())).thenAnswer(i -> history);
        when(checks.check(any(), anyMap(), any())).thenReturn(new Check(true, null, List.of(),
                new PermissionVerdict(true, "role:billing:admin", "held", List.of()),
                PolicyVerdict.none(), new Resolver.Resolved()));
        when(client.callAsMachine(eq("billing"), anyString(), anyString(), anyMap(), any(), anyMap()))
                .thenReturn(new ComponentClient.Reply(201, JSON.readTree("{\"id\":\"cn-1\",\"state\":\"issued\"}"), ""));
        when(client.call(eq("billing"), anyString(), anyString(), anyMap(), anyMap(), any(), any(), anyMap()))
                .thenReturn(new ComponentClient.Reply(201, JSON.readTree("{\"id\":\"cn-1\",\"state\":\"issued\"}"), ""));
        when(client.callAsMachine(eq("workforce"), anyString(), anyString(), anyMap(), any(), anyMap()))
                .thenReturn(new ComponentClient.Reply(201, JSON.readTree("{\"id\":\"appr-1\"}"), ""));
        UpgradeService upgrades = mock(UpgradeService.class);
        when(upgrades.availableUpgrades(any(Resolver.Resolved.class), any())).thenReturn(List.of());
        service = new ActionExecuteService(registry, checks, client, mock(ReceiptPublisher.class), upgrades, JSON,
                new ReceiptStore(rows), new ActionTracing(OpenTelemetry.noop()));
    }

    private static Map<String, JsonNode> CREDIT_ACTIONS() {
        Map<String, JsonNode> m = new TreeMap<>();
        m.put("issueCredit", CREDIT);
        return m;
    }

    /** What machines already credited on a bill, two days ago, without a person. */
    private void machineAlreadyCredited(String bill, String amount) {
        ActionReceiptRow r = new ActionReceiptRow("id-" + history.size(), "r-" + history.size(), ActionReceiptRow.ATTEMPT,
                "genalpha", "issueCredit", OffsetDateTime.now().minusDays(2));
        r.setInputs("{\"billId\":\"" + bill + "\",\"amount\":\"" + amount + "\",\"reason\":\"goodwill\"}");
        r.setAgent("finance-copilot");
        r.setAuthority("billing:admin,agent:declared");
        history.add(r);
    }

    private ActionExecuteService.Outcome credit(Caller caller, String bill, String amount) {
        return service.execute(CREDIT, Map.of("billId", bill, "amount", amount, "reason", "goodwill"), caller);
    }

    /** A finance approver's token, driven by a copilot that names itself. */
    private static Caller approverToken_drivenByAnAgent() {
        return new Caller("t", "fiona", Set.of("billing:admin"), "console", "genalpha", "finance-copilot");
    }

    private static Caller approverPerson() {
        return new Caller("t", "fiona", Set.of("billing:admin"), "console", "genalpha", null);
    }

    private static Caller badgedWorker() {
        return new Caller("t", "bss-worker", Set.of("agent", "workforce:use"), "console", "genalpha", null);
    }

    @SuppressWarnings("unchecked")
    private String filedReason() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(client).callAsMachine(eq("workforce"), eq("POST"), anyString(), anyMap(), body.capture(), anyMap());
        return String.valueOf(((Map<String, Object>) body.getValue()).get("reason"));
    }

    @Test
    void anApproversTokenDrivenByAMachineStillMeetsTheBudgetPerTarget() {
        machineAlreadyCredited("bill-1", "20");
        ActionExecuteService.Outcome out = credit(approverToken_drivenByAnAgent(), "bill-1", "20");
        assertThat(out.status()).as("a second machine credit on the same bill goes to a person, approver role or not")
                .isEqualTo(202);
        verify(client, never()).callAsMachine(eq("billing"), anyString(), anyString(), anyMap(), any(), anyMap());
        verify(client, never()).call(eq("billing"), anyString(), anyString(), anyMap(), anyMap(), any(), any(), anyMap());
    }

    @Test
    void anApproverPersonIsExempt() {
        machineAlreadyCredited("bill-1", "20");
        ActionExecuteService.Outcome out = credit(approverPerson(), "bill-1", "40");
        assertThat(out.done()).as("a person holding the approver role decides it themselves").isTrue();
    }

    @Test
    void aFilingByTheBudgetSaysTheBudgetWasTheReason_notTheThreshold() {
        machineAlreadyCredited("bill-1", "20");
        ActionExecuteService.Outcome out = credit(badgedWorker(), "bill-1", "10");
        assertThat(out.status()).isEqualTo(202);
        String reason = filedReason();
        assertThat(reason).as("10 is under 25: 'above the threshold' would be false")
                .doesNotContain("above the threshold");
        assertThat(reason).contains("30 days").contains("20").contains("10").contains("30").contains("25")
                .contains("bill-1");
        assertThat(out.body().said()).doesNotContain("above the threshold");
    }

    @Test
    void aFilingAboveTheLineStillSaysSo_withTheAmount() {
        ActionExecuteService.Outcome out = credit(badgedWorker(), "bill-2", "40");
        assertThat(out.status()).isEqualTo(202);
        assertThat(filedReason()).contains("40").contains("above the threshold of 25");
    }
}
