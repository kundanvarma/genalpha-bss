package com.bss.ontology.tracing;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.dto.Check;
import com.bss.ontology.dto.PermissionVerdict;
import com.bss.ontology.dto.PolicyVerdict;
import com.bss.ontology.receipt.ActionReceiptRepository;
import com.bss.ontology.receipt.ActionReceiptRow;
import com.bss.ontology.receipt.ReceiptStore;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.service.ActionCheckService;
import com.bss.ontology.service.ActionExecuteService;
import com.bss.ontology.service.Caller;
import com.bss.ontology.service.ReceiptPublisher;
import com.bss.ontology.service.Resolver;
import com.bss.ontology.service.UpgradeService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #422: a governed action's execute is one {@code execute_tool} span, and the
 * receipt rows and the decision it writes carry that span's trace id — the one
 * key that joins a trace, a receipt and a decision-log row.
 */
class ExecuteToolSpanTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET_INPUT = "sub-paula-family-42";

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .build();
    private final ActionReceiptRepository rows = mock(ActionReceiptRepository.class);
    private final ReceiptPublisher publisher = mock(ReceiptPublisher.class);
    private final ActionCheckService checks = mock(ActionCheckService.class);
    private final ComponentClient client = mock(ComponentClient.class);
    private final Registry registry = mock(Registry.class);

    @AfterEach
    void close() {
        otel.close();
    }

    private static final JsonNode ACTION = JSON.readTree("""
            {"action":"suspendService","concept":"Subscription","version":2,
             "executes":{"capability":"inventory.suspend",
                         "pathVars":{"id":"${subscriptionId}"},
                         "template":{"state":"suspended"}},
             "governance":{"autonomy":"medium"}}""");

    private ActionExecuteService service() {
        Map<String, JsonNode> caps = new TreeMap<>();
        caps.put("inventory.suspend", JSON.readTree("""
                {"id":"inventory.suspend","component":"product-inventory",
                 "route":{"method":"PATCH","path":"/product/{id}"}}"""));
        when(registry.forTenant(anyString())).thenReturn(new Registry.Layer(new TreeMap<>(), new TreeMap<>(),
                caps, new TreeMap<>(), new TreeMap<>()));
        when(rows.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        UpgradeService upgrades = mock(UpgradeService.class);
        when(upgrades.availableUpgrades(any(Resolver.Resolved.class), any())).thenReturn(List.of());
        return new ActionExecuteService(registry, checks, client, publisher, upgrades, JSON,
                new ReceiptStore(rows), new ActionTracing(otel));
    }

    private static Check check(boolean allowed) {
        return new Check(allowed, allowed ? null : "the subscription is already suspended", List.of(),
                new PermissionVerdict(true, "service:write", "held", List.of()),
                new PolicyVerdict("ontology", "allow", null, null, "allowed"), new Resolver.Resolved());
    }

    private static Caller caller() {
        return new Caller("token", "pat", Set.of("service:write"), "csr-console", "genalpha", null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decision() {
        ArgumentCaptor<Map<String, Object>> d = ArgumentCaptor.forClass(Map.class);
        verify(publisher).decision(eq("genalpha"), d.capture());
        return d.getValue();
    }

    private List<ActionReceiptRow> receiptRows() {
        ArgumentCaptor<ActionReceiptRow> r = ArgumentCaptor.forClass(ActionReceiptRow.class);
        verify(rows, atLeastOnce()).saveAndFlush(r.capture());
        return r.getAllValues();
    }

    @Test
    void anExecutedActionIsOneExecuteToolSpanJoinedToItsReceiptAndDecision() {
        when(checks.check(any(), anyMap(), any())).thenReturn(check(true));
        when(client.call(anyString(), anyString(), anyString(), anyMap(), anyMap(), any(), any(), anyMap()))
                .thenReturn(new ComponentClient.Reply(200,
                        JSON.readTree("{\"id\":\"order-9\",\"state\":\"acknowledged\"}"), ""));

        ActionExecuteService.Outcome out = service().execute(ACTION, Map.of("subscriptionId", SECRET_INPUT), caller());
        assertThat(out.done()).isTrue();

        assertThat(spans.getFinishedSpanItems()).hasSize(1);
        SpanData span = spans.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("execute_tool suspendService");
        assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("execute_tool");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name"))).isEqualTo("suspendService");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("bss.tenant"))).isEqualTo("genalpha");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("bss.action.outcome"))).isEqualTo("executed");
        assertThat(span.getAttributes().get(AttributeKey.longKey("bss.action.status"))).isEqualTo(200L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("bss.decision_id")))
                .isEqualTo(out.body().decisionId()).startsWith("ontology-");
        assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);

        // the receipt store's rows (attempt + outcome) carry the span's trace id,
        // and the span names the receipt
        List<ActionReceiptRow> written = receiptRows();
        assertThat(written).hasSize(2).allSatisfy(r -> assertThat(r.getTraceId()).isEqualTo(span.getTraceId()));
        assertThat(span.getAttributes().get(AttributeKey.stringKey("bss.receipt_id")))
                .isEqualTo(written.get(0).getReceiptId());

        // the decision for insight's log carries it in context, beside the idempotency key
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) decision().get("context");
        assertThat(context.get("traceId")).isEqualTo(span.getTraceId());

        // inputs never reach the span
        assertThat(String.valueOf(span.getAttributes().asMap().values())).doesNotContain(SECRET_INPUT);
    }

    @Test
    void aRefusedActionIsTracedTooAndItsRefusalRowCarriesTheTraceId() {
        when(checks.check(any(), anyMap(), any())).thenReturn(check(false));

        ActionExecuteService.Outcome out = service().execute(ACTION, Map.of("subscriptionId", SECRET_INPUT), caller());
        assertThat(out.status()).isEqualTo(422);

        SpanData span = spans.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("bss.action.outcome"))).isEqualTo("refused");
        // a governed refusal is an answer, not an error
        assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("error.type"))).isNull();
        // and its words (which can name a customer's things) stay on the receipt
        assertThat(String.valueOf(span.getAttributes().asMap().values())).doesNotContain("already suspended");

        assertThat(receiptRows()).singleElement()
                .satisfies(r -> assertThat(r.getTraceId()).isEqualTo(span.getTraceId()));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) decision().get("context");
        assertThat(context.get("traceId")).isEqualTo(span.getTraceId());
    }

    @Test
    void theTraceIdIsOutsideTheHashChain() {
        ActionReceiptRow row = new ActionReceiptRow("id-1", "r-1", ActionReceiptRow.ATTEMPT,
                "genalpha", "suspendService", java.time.OffsetDateTime.parse("2026-11-02T10:00:00Z"));
        String before = canonical(row);
        row.setTraceId("0af7651916cd43dd8448eb211c80319c");
        assertThat(canonical(row)).as("rows hashed before #422 must stay verifiable").isEqualTo(before);
    }

    private static String canonical(ActionReceiptRow row) {
        try {
            var m = ReceiptStore.class.getDeclaredMethod("canonical", ActionReceiptRow.class);
            m.setAccessible(true);
            return (String) m.invoke(null, row);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
