package com.bss.intelligence.tracing;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.audit.AiContractRepository;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import com.bss.intelligence.service.Redactor;
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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #422: a model call is a GenAI span, the span carries names and numbers and
 * never words, and the ledger row is keyed by the span's trace id.
 */
class AiCallTracingTest {

    private static final String SYSTEM = "You draft a reply for the care desk. Secret system wording.";
    private static final String USER = "Ticket note: customer anna@example.com, phone +47 912 34 567, says the router blinks.";
    private static final String ANSWER = "Reply to <email#1>: please restart the router.";

    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .build();
    private final AiAuditRepository audits = mock(AiAuditRepository.class);

    @AfterEach
    void close() {
        otel.close();
    }

    static final class Model implements LlmAdapter {
        RuntimeException failWith;

        @Override
        public String complete(String system, String user) {
            if (failWith != null) {
                throw failWith;
            }
            return ANSWER;
        }

        @Override
        public String provider() {
            return "anthropic";
        }

        @Override
        public String model() {
            return "claude-test-1";
        }
    }

    private AiGovernor governor(LlmAdapter model) {
        AiBudgetRepository budgets = mock(AiBudgetRepository.class);
        when(budgets.findByTenantId(anyString())).thenReturn(Optional.empty());
        AiContractRepository contracts = mock(AiContractRepository.class);
        when(contracts.findByTenantIdAndUseCase(anyString(), anyString())).thenReturn(Optional.empty());
        TenantScope scope = mock(TenantScope.class);
        when(scope.currentTenantId()).thenReturn("genalpha");
        TenantRegistry registry = new TenantRegistry();
        TenantRegistry.TenantEntry entry = new TenantRegistry.TenantEntry();
        entry.setId("genalpha");
        registry.setRegistry(List.of(entry));
        return new AiGovernor(model, audits, budgets, contracts, scope, new Redactor(),
                mock(PlatformTransactionManager.class), 2000L, registry, new MockEnvironment(),
                new GenAiTracing(otel));
    }

    private AiAudit ledgerRow() {
        ArgumentCaptor<AiAudit> row = ArgumentCaptor.forClass(AiAudit.class);
        verify(audits).save(row.capture());
        return row.getValue();
    }

    @Test
    void aModelCallIsOneGenAiSpanWithTheStandardAttributes() {
        governor(new Model()).complete("ticket-reply", LlmAdapter.Tier.FAST, SYSTEM, USER);

        assertThat(spans.getFinishedSpanItems()).hasSize(1);
        SpanData span = spans.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("chat claude-test-1");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name"))).isEqualTo("chat");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name"))).isEqualTo("anthropic");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("claude-test-1");
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isPositive();
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isPositive();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("bss.tenant"))).isEqualTo("genalpha");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("bss.use_case"))).isEqualTo("ticket-reply");
        assertThat(span.getAttributes().get(AttributeKey.booleanKey("bss.usage.estimated"))).isTrue();
        assertThat(span.getStatus().getStatusCode()).isNotEqualTo(StatusCode.ERROR);
    }

    @Test
    void theSpanCarriesNoContentAtAll() {
        governor(new Model()).complete("ticket-reply", LlmAdapter.Tier.FAST, SYSTEM, USER);

        SpanData span = spans.getFinishedSpanItems().get(0);
        // none of the conventions' opt-in content attributes, nor the older names
        assertThat(span.getAttributes().asMap().keySet())
                .extracting(AttributeKey::getKey)
                .noneMatch(k -> k.startsWith("gen_ai.input") || k.startsWith("gen_ai.output")
                        || k.startsWith("gen_ai.prompt") || k.startsWith("gen_ai.completion")
                        || k.startsWith("gen_ai.system_instructions"));
        // and no attribute value, of any name, holds a word of the prompt or the answer
        String everything = String.valueOf(span.getAttributes().asMap().values()) + span.getName()
                + span.getEvents();
        assertThat(everything).doesNotContain("anna@example.com", "912 34 567", "router", "Secret system",
                "<email#1>", "restart");
    }

    @Test
    void theLedgerRowIsKeyedByTheSpansTraceId() {
        governor(new Model()).complete("ticket-reply", LlmAdapter.Tier.FAST, SYSTEM, USER);

        SpanData span = spans.getFinishedSpanItems().get(0);
        AiAudit row = ledgerRow();
        assertThat(row.getTraceId()).isEqualTo(span.getTraceId()).hasSize(32);
    }

    @Test
    void aFailedCallIsAnErrorSpanWithTheClassNotTheMessage() {
        Model model = new Model();
        model.failWith = new IllegalStateException("provider said: anna@example.com is not allowed");

        assertThatThrownBy(() -> governor(model).complete("ticket-reply", LlmAdapter.Tier.FAST, SYSTEM, USER))
                .isInstanceOf(IllegalStateException.class);

        SpanData span = spans.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("error.type")))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(span.getEvents()).isEmpty(); // no recordException: its message could quote the prompt
        assertThat(String.valueOf(span.getAttributes().asMap().values())).doesNotContain("anna@example.com");
        assertThat(ledgerRow().getTraceId()).isEqualTo(span.getTraceId());
    }

    @Test
    void withNoTracingTheRowHasNoTraceIdRatherThanZeros() {
        AiGovernor plain = new AiGovernor(new Model(), audits, budgetsNone(), contractsNone(), tenantScope(),
                new Redactor(), mock(PlatformTransactionManager.class), 2000L, new TenantRegistry(),
                new MockEnvironment(), new GenAiTracing(io.opentelemetry.api.OpenTelemetry.noop()));
        plain.complete("ticket-reply", LlmAdapter.Tier.FAST, SYSTEM, USER);
        assertThat(ledgerRow().getTraceId()).isNull();
    }

    private static AiBudgetRepository budgetsNone() {
        AiBudgetRepository budgets = mock(AiBudgetRepository.class);
        when(budgets.findByTenantId(anyString())).thenReturn(Optional.empty());
        return budgets;
    }

    private static AiContractRepository contractsNone() {
        AiContractRepository contracts = mock(AiContractRepository.class);
        when(contracts.findByTenantIdAndUseCase(anyString(), anyString())).thenReturn(Optional.empty());
        return contracts;
    }

    private static TenantScope tenantScope() {
        TenantScope scope = mock(TenantScope.class);
        when(scope.currentTenantId()).thenReturn("genalpha");
        return scope;
    }
}
