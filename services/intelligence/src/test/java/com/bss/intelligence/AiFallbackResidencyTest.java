package com.bss.intelligence;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.audit.AiContractRepository;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmAdapter;
import com.bss.intelligence.llm.LlmRouter;
import com.bss.intelligence.llm.StubAdapter;
import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import com.bss.intelligence.service.Redactor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #422 through the real router and the real HTTP adapters: two stand-in
 * providers on loopback ports, the tenant's registry entry as an operator
 * would write it, and the assertions on the ledger rows and on what each
 * stand-in actually received.
 */
class AiFallbackResidencyTest {

    private static final String ANTHROPIC_OK = """
            {"content":[{"type":"text","text":"anthropic says hi"}],
             "usage":{"input_tokens":100,"output_tokens":20,"cache_read_input_tokens":30}}""";
    private static final String OPENAI_OK = """
            {"choices":[{"message":{"role":"assistant","content":"fallback says hi"}}],
             "usage":{"prompt_tokens":70,"completion_tokens":9,"total_tokens":79}}""";

    /** A stand-in provider: answers every call with one status and body, counts calls, keeps headers. */
    static final class Provider {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> apiKeys = new ArrayList<>();

        Provider(int status, String body) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                apiKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
                exchange.getRequestBody().readAllBytes();
                byte[] reply = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, reply.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(reply);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    private final List<Provider> started = new ArrayList<>();
    private final AiAuditRepository audits = mock(AiAuditRepository.class);

    private Provider provider(int status, String body) {
        Provider p = new Provider(status, body);
        started.add(p);
        return p;
    }

    @AfterEach
    void stop() {
        started.forEach(p -> p.server.stop(0));
    }

    private AiGovernor governor(Consumer<TenantRegistry.TenantEntry> config) {
        return governor(new StubAdapter(), RestClient.builder(), config);
    }

    /** The governor over the real router, with this default model and this HTTP builder. */
    private AiGovernor governor(LlmAdapter defaultModel, RestClient.Builder http,
            Consumer<TenantRegistry.TenantEntry> config) {
        TenantRegistry.TenantEntry entry = new TenantRegistry.TenantEntry();
        entry.setId("t1");
        config.accept(entry);
        TenantRegistry registry = new TenantRegistry();
        registry.setRegistry(List.of(entry));
        TenantScope scope = mock(TenantScope.class);
        when(scope.currentTenantId()).thenReturn("t1");
        AiBudgetRepository budgets = mock(AiBudgetRepository.class);
        when(budgets.findByTenantId(anyString())).thenReturn(Optional.empty());
        AiContractRepository contracts = mock(AiContractRepository.class);
        when(contracts.findByTenantIdAndUseCase(anyString(), anyString())).thenReturn(Optional.empty());
        LlmRouter router = new LlmRouter(List.of(defaultModel), registry, scope, http);
        return new AiGovernor(router, audits, budgets, contracts, scope, new Redactor(),
                mock(PlatformTransactionManager.class), 2000L, registry, new MockEnvironment(),
                new com.bss.intelligence.tracing.GenAiTracing(io.opentelemetry.api.OpenTelemetry.noop()));
    }

    private List<AiAudit> ledger() {
        ArgumentCaptor<AiAudit> rows = ArgumentCaptor.forClass(AiAudit.class);
        verify(audits, atLeastOnce()).save(rows.capture());
        return rows.getAllValues();
    }

    private static Consumer<TenantRegistry.TenantEntry> anthropicAt(Provider p) {
        return t -> {
            t.setAiProvider("anthropic");
            t.setAiBaseUrl(p.url());
            t.setAiApiKey("primary-key");
            t.setAiModel("claude-test");
        };
    }

    /* ------------------------------------------------------------ fallback */

    @Test
    void aFallbackServesWhenThePrimaryAnswers503AndTheLedgerSaysSo() {
        Provider primary = provider(503, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
        Provider fallback = provider(200, OPENAI_OK);
        AiGovernor governor = governor(anthropicAt(primary).andThen(t -> {
            t.setAiFallbackProvider("openai-compatible");
            t.setAiFallbackBaseUrl(fallback.url());
            t.setAiFallbackModel("mistral-small");
        }));

        String answer = governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "draft a reply");

        assertThat(answer).isEqualTo("fallback says hi");
        assertThat(primary.calls).hasValue(1);
        assertThat(fallback.calls).hasValue(1);
        // the fallback never sees the primary's key (the shared-builder header leak)
        assertThat(fallback.apiKeys).containsExactly("null");
        List<AiAudit> rows = ledger();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getOutcome()).isEqualTo("error");
        assertThat(rows.get(0).getProvider()).isEqualTo("anthropic");
        assertThat(rows.get(0).getFallbackFrom()).isNull();
        AiAudit served = rows.get(1);
        assertThat(served.getOutcome()).isEqualTo("ok");
        assertThat(served.getProvider()).isEqualTo("openai-compatible");
        assertThat(served.getModel()).isEqualTo("mistral-small");
        assertThat(served.getFallbackFrom()).isEqualTo("anthropic");
        assertThat(served.getJurisdiction()).isEqualTo("self-hosted");
        assertThat(served.getResponse()).isEqualTo("fallback says hi");
    }

    @Test
    void aFallbackServesOn429AndOnARefusedConnection() {
        Provider limited = provider(429, "{\"error\":\"rate limited\"}");
        Provider fallback = provider(200, OPENAI_OK);
        Consumer<TenantRegistry.TenantEntry> withFallback = t -> {
            t.setAiFallbackProvider("openai-compatible");
            t.setAiFallbackBaseUrl(fallback.url());
            t.setAiFallbackModel("mistral-small");
        };
        assertThat(governor(anthropicAt(limited).andThen(withFallback))
                .complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u")).isEqualTo("fallback says hi");

        Provider gone = provider(200, ANTHROPIC_OK);
        gone.server.stop(0); // nothing listens: connection refused
        assertThat(governor(anthropicAt(gone).andThen(withFallback))
                .complete("ticket-reply", LlmAdapter.Tier.SMART, "sys", "u")).isEqualTo("fallback says hi");
        assertThat(fallback.calls).hasValue(2);
    }

    @Test
    void aClientErrorIsNotRetriedElsewhere() {
        Provider primary = provider(400, "{\"error\":\"bad request\"}");
        Provider fallback = provider(200, OPENAI_OK);
        AiGovernor governor = governor(anthropicAt(primary).andThen(t -> {
            t.setAiFallbackProvider("openai-compatible");
            t.setAiFallbackBaseUrl(fallback.url());
            t.setAiFallbackModel("mistral-small");
        }));

        assertThatThrownBy(() -> governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u"))
                .isInstanceOf(HttpClientErrorException.class);
        assertThat(fallback.calls).hasValue(0);
    }

    @Test
    void withNoFallbackConfiguredA503FailsExactlyAsBefore() {
        Provider primary = provider(503, "{}");
        AiGovernor governor = governor(anthropicAt(primary));

        assertThatThrownBy(() -> governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u"))
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(primary.calls).hasValue(1);
        List<AiAudit> rows = ledger();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOutcome()).isEqualTo("error");
        assertThat(rows.get(0).getProvider()).isEqualTo("anthropic");
        assertThat(rows.get(0).getJurisdiction()).isEqualTo("US");
        assertThat(rows.get(0).getFallbackFrom()).isNull();
    }

    @Test
    void aTierFallbackWinsOverTheSharedOne() {
        Provider primary = provider(503, "{}");
        Provider shared = provider(200, OPENAI_OK);
        Provider fastOnly = provider(200, OPENAI_OK);
        AiGovernor governor = governor(anthropicAt(primary).andThen(t -> {
            t.setAiFallbackProvider("openai-compatible");
            t.setAiFallbackBaseUrl(shared.url());
            t.setAiFallbackModel("shared-model");
            t.setAiFallbackBaseUrlFast(fastOnly.url());
            t.setAiFallbackModelFast("fast-model");
        }));

        governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u");
        governor.complete("ticket-reply", LlmAdapter.Tier.SMART, "sys", "u");

        assertThat(fastOnly.calls).hasValue(1);
        assertThat(shared.calls).hasValue(1);
    }

    @Test
    void aProviderErrorThatQuotesThePromptNeverReachesTheLog() {
        String secret = "the customer's private complaint about invoice 4471";
        // a provider whose error body quotes the prompt back — common in 4xx/5xx detail text
        Provider primary = provider(503, "{\"error\":{\"message\":\"overloaded while reading: " + secret + "\"}}");
        Provider fallback = provider(200, OPENAI_OK);
        AiGovernor governor = governor(anthropicAt(primary).andThen(t -> {
            t.setAiRawExposure(true); // raw exposure: the prompt leaves unredacted
            t.setAiFallbackProvider("openai-compatible");
            t.setAiFallbackBaseUrl(fallback.url());
            t.setAiFallbackModel("mistral-small");
        }));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiGovernor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThat(governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", secret))
                    .isEqualTo("fallback says hi");
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(logs.list).isNotEmpty(); // the fallback is still logged…
        assertThat(logs.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .allMatch(m -> m.contains("503")) // …with the provider's status…
                .noneMatch(m -> m.contains(secret) || m.contains("invoice")); // …and never its words
    }

    /* ------------------------------------------------------------ residency */

    @Test
    void anEuOnlyTenantIsRefusedAUsProviderBeforeAnythingLeaves() {
        Provider primary = provider(200, ANTHROPIC_OK);
        AiGovernor governor = governor(anthropicAt(primary).andThen(t -> t.setAiDataResidency("eu")));

        assertThatThrownBy(() -> governor.complete("ticket-reply", LlmAdapter.Tier.SMART, "sys", "u"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("must stay in the EU")
                .hasMessageContaining("jurisdiction US");
        assertThat(primary.calls).hasValue(0);
        AiAudit row = ledger().get(0);
        assertThat(row.getOutcome()).isEqualTo("refused-residency");
        assertThat(row.getJurisdiction()).isEqualTo("US");
        assertThat(row.getRawExposure()).isFalse();
    }

    @Test
    void anEuOnlyTenantIsServedByAnEndpointDeclaredEu() {
        Provider primary = provider(200, ANTHROPIC_OK);
        AiGovernor governor = governor(anthropicAt(primary).andThen(t -> {
            t.setAiDataResidency("EU");
            t.setAiJurisdiction("eu");
        }));

        assertThat(governor.complete("ticket-reply", LlmAdapter.Tier.SMART, "sys", "u"))
                .isEqualTo("anthropic says hi");
        assertThat(ledger().get(0).getJurisdiction()).isEqualTo("EU");
    }

    @Test
    void aTierOnAnotherProviderDoesNotInheritTheSharedEuDeclaration() {
        Provider eu = provider(200, OPENAI_OK);
        Provider us = provider(200, ANTHROPIC_OK);
        AiGovernor governor = governor(t -> {
            t.setAiDataResidency("eu");
            t.setAiProvider("openai-compatible");
            t.setAiBaseUrl(eu.url());
            t.setAiModel("mistral-small");
            t.setAiJurisdiction("EU");
            t.setAiProviderSmart("anthropic");
            t.setAiBaseUrlSmart(us.url());
            t.setAiModelSmart("claude-test");
        });

        assertThat(governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u")).isEqualTo("fallback says hi");
        assertThatThrownBy(() -> governor.complete("ticket-reply", LlmAdapter.Tier.SMART, "sys", "u"))
                .hasMessageContaining("jurisdiction US");
        assertThat(us.calls).hasValue(0);
    }

    @Test
    void anEuOnlyTenantNeverFallsBackToAUsProvider() {
        Provider primary = provider(503, "{}");
        Provider usFallback = provider(200, ANTHROPIC_OK);
        AiGovernor governor = governor(t -> {
            t.setAiDataResidency("eu");
            t.setAiProvider("openai-compatible");
            t.setAiBaseUrl(primary.url());
            t.setAiModel("mistral-small");
            t.setAiJurisdiction("EU");
            t.setAiFallbackProvider("anthropic");
            t.setAiFallbackBaseUrl(usFallback.url());
            t.setAiFallbackApiKey("fallback-key");
            t.setAiFallbackModel("claude-test");
        });

        // the first failure stands: the caller sees the provider's 503, not a US answer
        assertThatThrownBy(() -> governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u"))
                .isInstanceOf(HttpServerErrorException.class);
        assertThat(usFallback.calls).hasValue(0);
        List<AiAudit> rows = ledger();
        assertThat(rows).extracting(AiAudit::getOutcome).containsExactly("error", "refused-residency");
        assertThat(rows.get(1).getProvider()).isEqualTo("anthropic");
        assertThat(rows.get(1).getJurisdiction()).isEqualTo("US");
        assertThat(rows.get(1).getFallbackFrom()).isEqualTo("openai-compatible");
    }

    @Test
    void aResidencyWordThisBssDoesNotKnowRefusesRatherThanMeaningAny() {
        Provider primary = provider(200, OPENAI_OK);
        AiGovernor governor = governor(t -> {
            t.setAiDataResidency("europe-only");
            t.setAiProvider("openai-compatible");
            t.setAiBaseUrl(primary.url());
            t.setAiModel("local");
        });

        assertThatThrownBy(() -> governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u"))
                .hasMessageContaining("not a residency rule this BSS knows");
        assertThat(primary.calls).hasValue(0);
        assertThat(ledger().get(0).getOutcome()).isEqualTo("refused-residency");
    }

    /* ------------------------------------- residency follows the endpoint (#2, #9) */

    /** A builder whose requests record where they would have gone, and send nothing. */
    private static RestClient.Builder offline(List<java.net.URI> attempted) {
        return RestClient.builder().requestFactory((uri, method) -> {
            attempted.add(uri);
            throw new IOException("offline: the test sends nothing");
        });
    }

    @Test
    void anOpenAiCompatibleStackAtOpenAisPublicEndpointIsUsAndAnEuOnlyTenantIsRefused() {
        List<java.net.URI> attempted = new ArrayList<>();
        AiGovernor governor = governor(new StubAdapter(), offline(attempted), t -> {
            t.setAiDataResidency("eu");
            t.setAiProvider("openai-compatible");
            t.setAiBaseUrl("https://api.openai.com");
            t.setAiApiKey("tenant-key");
            t.setAiModel("gpt-4o-mini");
        });

        assertThatThrownBy(() -> governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("jurisdiction US");
        assertThat(attempted).isEmpty();
        AiAudit row = ledger().get(0);
        assertThat(row.getOutcome()).isEqualTo("refused-residency");
        assertThat(row.getJurisdiction()).isEqualTo("US");
    }

    @Test
    void aDeclarationCannotMoveAPublicUsEndpointIntoTheEu() {
        List<java.net.URI> attempted = new ArrayList<>();
        AiGovernor governor = governor(new StubAdapter(), offline(attempted), t -> {
            t.setAiDataResidency("eu");
            t.setAiProvider("anthropic"); // no ai-base-url: the adapter calls https://api.anthropic.com
            t.setAiApiKey("tenant-key");
            t.setAiModel("claude-test");
            t.setAiJurisdiction("EU");
        });

        assertThatThrownBy(() -> governor.complete("ticket-reply", LlmAdapter.Tier.SMART, "sys", "u"))
                .hasMessageContaining("jurisdiction US");
        assertThat(attempted).isEmpty();
    }

    @Test
    void theSharedDefaultModelAtOpenAisPublicEndpointIsUsForATenantWithoutItsOwnStack() {
        List<java.net.URI> attempted = new ArrayList<>();
        // the deployment's default: AI_PROVIDER=openai-compatible, AI_BASE_URL=https://api.openai.com
        LlmAdapter shared = new com.bss.intelligence.llm.OpenAiCompatibleAdapter(offline(attempted),
                "https://api.openai.com", "deployment-key", "gpt-4o-mini");
        AiGovernor governor = governor(shared, RestClient.builder(), t -> t.setAiDataResidency("eu"));

        assertThatThrownBy(() -> governor.complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("jurisdiction US");
        assertThat(attempted).isEmpty();
        assertThat(ledger().get(0).getJurisdiction()).isEqualTo("US");
    }

    @Test
    void theSharedDefaultModelDeclaresItsJurisdictionWithOneSetting() {
        TenantRegistry registry = new TenantRegistry();
        TenantRegistry.TenantEntry entry = new TenantRegistry.TenantEntry();
        entry.setId("t1");
        registry.setRegistry(List.of(entry));
        TenantScope scope = mock(TenantScope.class);
        when(scope.currentTenantId()).thenReturn("t1");
        // a hosted endpoint the BSS cannot recognise: the operator says where it is
        LlmAdapter hosted = new com.bss.intelligence.llm.OpenAiCompatibleAdapter(RestClient.builder(),
                "https://llm.example-eu-host.test", "", "mistral-small");

        assertThat(new LlmRouter(List.of(hosted), registry, scope, RestClient.builder(), "")
                .route(LlmAdapter.Tier.FAST).jurisdiction()).isEqualTo("self-hosted"); // undeclared: the kind's default
        assertThat(new LlmRouter(List.of(hosted), registry, scope, RestClient.builder(), "eu")
                .route(LlmAdapter.Tier.FAST).jurisdiction()).isEqualTo("EU");
        assertThat(new LlmRouter(List.of(hosted), registry, scope, RestClient.builder(), "US")
                .route(LlmAdapter.Tier.SMART).jurisdiction()).isEqualTo("US");
        // and no declaration moves a public US endpoint
        LlmAdapter openAi = new com.bss.intelligence.llm.OpenAiCompatibleAdapter(RestClient.builder(),
                "https://api.openai.com", "", "gpt-4o-mini");
        assertThat(new LlmRouter(List.of(openAi), registry, scope, RestClient.builder(), "EU")
                .route(LlmAdapter.Tier.FAST).jurisdiction()).isEqualTo("US");
        // the stub sends nothing, whatever is declared
        assertThat(new LlmRouter(List.of(new StubAdapter()), registry, scope, RestClient.builder(), "US")
                .route(LlmAdapter.Tier.FAST).jurisdiction()).isEqualTo("none");
    }

    /* ------------------------------------------------------------ metering */

    @Test
    void anthropicUsageIsMeteredIncludingCachedInput() {
        Provider primary = provider(200, ANTHROPIC_OK);
        governor(anthropicAt(primary)).complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u");

        AiAudit row = ledger().get(0);
        assertThat(row.getTokenSource()).isEqualTo("provider");
        assertThat(row.getPromptTokens()).isEqualTo(130);
        assertThat(row.getCompletionTokens()).isEqualTo(20);
        assertThat(row.getCostMicros()).isEqualTo(150L * 2000L / 1000L);
    }

    @Test
    void openAiUsageIsMetered() {
        Provider primary = provider(200, OPENAI_OK);
        governor(t -> {
            t.setAiProvider("openai-compatible");
            t.setAiBaseUrl(primary.url());
            t.setAiModel("local");
        }).complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u");

        AiAudit row = ledger().get(0);
        assertThat(row.getTokenSource()).isEqualTo("provider");
        assertThat(row.getPromptTokens()).isEqualTo(70);
        assertThat(row.getCompletionTokens()).isEqualTo(9);
    }

    @Test
    void anAnswerWithoutUsageFallsBackToTheEstimateAndSaysSo() {
        Provider primary = provider(200, "{\"choices\":[{\"message\":{\"content\":\"twelve chars\"}}]}");
        governor(t -> {
            t.setAiProvider("openai-compatible");
            t.setAiBaseUrl(primary.url());
            t.setAiModel("local");
        }).complete("ticket-reply", LlmAdapter.Tier.FAST, "sys", "u");

        AiAudit row = ledger().get(0);
        assertThat(row.getTokenSource()).isEqualTo("estimate");
        assertThat(row.getCompletionTokens()).isEqualTo(3); // "twelve chars" = 12 chars / 4
    }
}
