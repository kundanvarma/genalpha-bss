package com.bss.intelligence;

import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.audit.AiContractRepository;
import com.bss.intelligence.llm.AiGovernor;
import com.bss.intelligence.llm.LlmRouter;
import com.bss.intelligence.llm.StubAdapter;
import com.bss.intelligence.llm.TenantVoice;
import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import com.bss.intelligence.service.CampaignCopy;
import com.bss.intelligence.service.CopilotRequests;
import com.bss.intelligence.service.CopyAssistantService;
import com.bss.intelligence.service.JourneyDraft;
import com.bss.intelligence.service.JourneyDraftService;
import com.bss.intelligence.service.Redactor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The provider and model a draft carries are its AI-content marker (#422): they
 * must name the model that WROTE it — the tier the call ran on, and the fallback
 * when the first choice was down — not whatever the tenant's unclassified tier is.
 */
class ServedModelMarkerTest {

    private static final String COPY = """
            {"choices":[{"message":{"role":"assistant","content":"SUBJECT: Welcome\\nBODY: Glad you are here."}}]}""";

    private final List<HttpServer> started = new ArrayList<>();

    private String provider(int status, String body, AtomicInteger calls) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] reply = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, reply.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(reply);
                }
            });
            server.start();
            started.add(server);
            return "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void stop() {
        started.forEach(s -> s.stop(0));
    }

    private record Desk(CopyAssistantService copy, JourneyDraftService journeys) {
    }

    private Desk desk(Consumer<TenantRegistry.TenantEntry> config) {
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
        LlmRouter router = new LlmRouter(List.of(new StubAdapter()), registry, scope, RestClient.builder());
        AiGovernor governor = new AiGovernor(router, mock(AiAuditRepository.class), budgets, contracts, scope,
                new Redactor(), mock(PlatformTransactionManager.class), 2000L, registry, new MockEnvironment(),
                new com.bss.intelligence.tracing.GenAiTracing(io.opentelemetry.api.OpenTelemetry.noop()));
        TenantVoice voice = new TenantVoice(scope, registry);
        return new Desk(new CopyAssistantService(new Redactor(), governor, voice),
                new JourneyDraftService(new Redactor(), governor, voice));
    }

    /** FAST on a local endpoint, SMART on another provider and model entirely. */
    private Consumer<TenantRegistry.TenantEntry> fastLocalSmartFrontier(String fastUrl, AtomicInteger smartCalls) {
        String smartUrl = provider(500, "{}", smartCalls);
        return t -> {
            t.setAiProvider("openai-compatible");
            t.setAiBaseUrl(fastUrl);
            t.setAiModel("fast-model");
            t.setAiProviderSmart("anthropic");
            t.setAiBaseUrlSmart(smartUrl);
            t.setAiApiKeySmart("smart-key");
            t.setAiModelSmart("smart-model");
        };
    }

    @Test
    void campaignCopyNamesTheFastModelThatWroteIt() {
        AtomicInteger fastCalls = new AtomicInteger();
        AtomicInteger smartCalls = new AtomicInteger();
        Desk desk = desk(fastLocalSmartFrontier(provider(200, COPY, fastCalls), smartCalls));

        CampaignCopy copy = desk.copy().draftCampaignCopy(
                new CopilotRequests.CopyBrief("welcome new customers", "Brand", null, null));

        assertThat(fastCalls).hasValue(1);
        assertThat(smartCalls).hasValue(0);
        assertThat(copy.provider()).isEqualTo("openai-compatible");
        assertThat(copy.model()).isEqualTo("fast-model");
    }

    @Test
    void aJourneyDraftNamesTheFastModelThatWroteIt() {
        AtomicInteger fastCalls = new AtomicInteger();
        AtomicInteger smartCalls = new AtomicInteger();
        Desk desk = desk(fastLocalSmartFrontier(provider(200, COPY, fastCalls), smartCalls));

        JourneyDraft draft = desk.journeys().draftJourney(
                new CopilotRequests.JourneyBrief("welcome new customers", "Brand", null));

        assertThat(smartCalls).hasValue(0);
        assertThat(draft.provider()).isEqualTo("openai-compatible");
        assertThat(draft.model()).isEqualTo("fast-model");
    }

    @Test
    void aCareDeskDraftNamesTheFastModelThatWroteIt() {
        // the CSR copilot's drafts say "Drafted by <provider> (<model>)" on the desk
        AtomicInteger fastCalls = new AtomicInteger();
        AtomicInteger smartCalls = new AtomicInteger();
        String reply = """
                {"choices":[{"message":{"role":"assistant","content":"REPLY: Sorry about the router."}}]}""";
        Consumer<TenantRegistry.TenantEntry> config = fastLocalSmartFrontier(provider(200, reply, fastCalls), smartCalls);
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
        AiGovernor governor = new AiGovernor(
                new LlmRouter(List.of(new StubAdapter()), registry, scope, RestClient.builder()),
                mock(AiAuditRepository.class), budgets, contracts, scope, new Redactor(),
                mock(PlatformTransactionManager.class), 2000L, registry, new MockEnvironment(),
                new com.bss.intelligence.tracing.GenAiTracing(io.opentelemetry.api.OpenTelemetry.noop()));
        tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        com.bss.intelligence.service.CopilotService copilot = new com.bss.intelligence.service.CopilotService(governor, json);

        com.bss.intelligence.service.TicketReplyDraft draft =
                copilot.draftTicketReply(json.readTree("{\"ticket\":{\"name\":\"Router is red\"}}"));

        assertThat(smartCalls).hasValue(0);
        assertThat(draft.provider()).isEqualTo("openai-compatible");
        assertThat(draft.model()).isEqualTo("fast-model");
    }

    @Test
    void whenTheFallbackWroteTheCopyTheCopyNamesTheFallback() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        String down = provider(503, "{}", primaryCalls);
        String fallback = provider(200, COPY, fallbackCalls);
        Desk desk = desk(t -> {
            t.setAiProvider("openai-compatible");
            t.setAiBaseUrl(down);
            t.setAiModel("primary-model");
            t.setAiFallbackProvider("openai-compatible");
            t.setAiFallbackBaseUrl(fallback);
            t.setAiFallbackModel("fallback-model");
        });

        CampaignCopy copy = desk.copy().draftCampaignCopy(
                new CopilotRequests.CopyBrief("welcome new customers", "Brand", null, null));

        assertThat(fallbackCalls).hasValue(1);
        assertThat(copy.model()).isEqualTo("fallback-model");
    }
}
