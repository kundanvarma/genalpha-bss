package com.bss.intelligence.door;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.bss.intelligence.audit.AiBudgetRepository;
import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import com.bss.intelligence.service.Redactor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The door's isolation guarantees, against stand-in providers on loopback
 * ports and the door service as the container builds it: ONE shared
 * RestClient.Builder (Spring hands each bean one prototype instance, and the
 * door keeps it for life).
 */
class ModelDoorIsolationTest {

    private static final String OPENAI_OK = """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],
             "usage":{"prompt_tokens":3,"completion_tokens":1}}""";
    private static final String ANTHROPIC_OK = """
            {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":3,"output_tokens":1}}""";

    /** A stand-in provider that keeps every credential it was shown. */
    static final class Provider {
        final HttpServer server;
        final Queue<String> credentials = new ConcurrentLinkedQueue<>();

        Provider(int status, String body) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.createContext("/", exchange -> {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                String apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
                credentials.add(auth != null ? auth : "x-api-key " + apiKey);
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
    private final Map<String, WorkerDoorKey> keysByHash = new ConcurrentHashMap<>();

    @AfterEach
    void stop() {
        started.forEach(p -> p.server.stop(0));
    }

    private Provider provider(int status, String body) {
        Provider p = new Provider(status, body);
        started.add(p);
        return p;
    }

    private static TenantRegistry.TenantEntry tenant(String id, Consumer<TenantRegistry.TenantEntry> config) {
        TenantRegistry.TenantEntry t = new TenantRegistry.TenantEntry();
        t.setId(id);
        config.accept(t);
        return t;
    }

    /** The door as the container builds it, around ONE builder; worker keys are minted per tenant below. */
    private ModelDoorService door(RestClient.Builder http, TenantRegistry.TenantEntry... entries) {
        TenantRegistry registry = new TenantRegistry();
        registry.setRegistry(List.of(entries));
        WorkerDoorKeyRepository keys = mock(WorkerDoorKeyRepository.class);
        when(keys.findByKeyHash(anyString())).thenAnswer(i -> Optional.ofNullable(keysByHash.get(i.<String>getArgument(0))));
        AiBudgetRepository budgets = mock(AiBudgetRepository.class);
        when(budgets.findByTenantId(anyString())).thenReturn(Optional.empty());
        return new ModelDoorService(keys, audits, budgets, registry, new TenantScope(registry),
                new DoorPricing(new MockEnvironment(), 2000L), new Redactor(), http, JsonMapper.builder().build(),
                "claude-haiku-4-5-20251001", "",
                new com.bss.intelligence.tracing.GenAiTracing(io.opentelemetry.api.OpenTelemetry.noop()));
    }

    /** A worker key for a tenant, as issue() would have stored it. */
    private String workerKey(String tenantId) {
        String key = "bssw_" + tenantId + "-worker";
        WorkerDoorKey row = new WorkerDoorKey();
        row.setId("k-" + tenantId);
        row.setTenantId(tenantId);
        row.setBadge("badge-" + tenantId);
        row.setKeyHash(ModelDoorService.sha256(key));
        keysByHash.put(row.getKeyHash(), row);
        return key;
    }

    /* ------------------------------------------------- finding 1: the shared builder */

    @Test
    void twoTenantsCallingAtOnceNeverSendOneTenantsKeyToTheOthersProvider() throws Exception {
        Provider anthropic = provider(200, ANTHROPIC_OK);
        Provider openai = provider(200, OPENAI_OK);
        ModelDoorService door = door(RestClient.builder(),
                tenant("tenant-a", t -> {
                    t.setWorkerAiProvider("anthropic");
                    t.setWorkerAiBaseUrl(anthropic.url());
                    t.setWorkerAiApiKey("key-of-tenant-a");
                    t.setWorkerAiModel("claude-haiku-4-5-20251001");
                }),
                tenant("tenant-b", t -> {
                    t.setWorkerAiProvider("openai-compatible");
                    t.setWorkerAiBaseUrl(openai.url());
                    t.setWorkerAiApiKey("key-of-tenant-b");
                    t.setWorkerAiModel("gpt-worker");
                }));
        String keyA = workerKey("tenant-a");
        String keyB = workerKey("tenant-b");
        String anthropicCall = "{\"model\":\"claude-haiku-4-5-20251001\",\"max_tokens\":8,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"tenant a's prompt\"}]}";
        String openAiCall = "{\"model\":\"gpt-worker\",\"messages\":[{\"role\":\"user\",\"content\":\"tenant b's prompt\"}]}";

        int calls = 400;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> replies = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            boolean a = i % 2 == 0;
            replies.add(pool.submit(() -> {
                go.await();
                return a ? door.anthropicMessages(keyA, anthropicCall, "2023-06-01", null).status()
                        : door.openAiChat("Bearer " + keyB, openAiCall).status();
            }));
        }
        go.countDown();
        for (Future<Integer> reply : replies) {
            reply.get();
        }
        pool.shutdown();

        // every call reached a provider, and each provider only ever saw its own tenant's key
        assertThat(anthropic.credentials.size() + openai.credentials.size()).isEqualTo(calls);
        assertThat(anthropic.credentials).containsOnly("x-api-key key-of-tenant-a");
        assertThat(openai.credentials).containsOnly("Bearer key-of-tenant-b");
    }

    /* ------------------------------------------- finding 2: the effective endpoint */

    @Test
    void aBlankOpenAiCompatibleBaseUrlIsOpenAisPublicEndpointSoAnEuOnlyTenantIsRefused() {
        // a request factory that records where the door would have sent the call, and sends nothing
        Queue<URI> attempted = new ConcurrentLinkedQueue<>();
        RestClient.Builder offline = RestClient.builder().requestFactory((uri, method) -> {
            attempted.add(uri);
            throw new IOException("offline: the test sends nothing");
        });
        ModelDoorService door = door(offline, tenant("tenant-eu", t -> {
            t.setAiDataResidency("eu");
            t.setWorkerAiProvider("openai-compatible");
            t.setWorkerAiApiKey("key-of-tenant-eu");
            t.setWorkerAiModel("gpt-worker");
            // no worker-ai-base-url: the door forwards to https://api.openai.com/v1
        }));

        DoorRequests.DoorReply reply = door.openAiChat("Bearer " + workerKey("tenant-eu"),
                "{\"model\":\"gpt-worker\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.body()).contains("must stay in the EU").contains("jurisdiction US");
        assertThat(attempted).isEmpty();
        assertThat(ledger()).extracting(AiAudit::getOutcome).containsExactly("refused-residency");
    }

    @Test
    void anEuOnlyTenantsSelfHostedOpenAiCompatibleWorkerModelIsStillServed() {
        Provider local = provider(200, OPENAI_OK);
        ModelDoorService door = door(RestClient.builder(), tenant("tenant-eu", t -> {
            t.setAiDataResidency("eu");
            t.setWorkerAiProvider("openai-compatible");
            t.setWorkerAiBaseUrl(local.url());
            t.setWorkerAiApiKey("local-key");
            t.setWorkerAiModel("gpt-worker");
        }));

        DoorRequests.DoorReply reply = door.openAiChat("Bearer " + workerKey("tenant-eu"),
                "{\"model\":\"gpt-worker\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(local.credentials).containsExactly("Bearer local-key");
    }

    /* ------------------------------------------------ finding 19: no prompt in a log */

    @Test
    void anUpstreamErrorThatQuotesThePromptNeverReachesTheLog() {
        String secret = "the customer's private complaint about invoice 4471";
        Provider echoing = provider(500, "{\"error\":{\"message\":\"cannot process: " + secret + "\"}}");
        ModelDoorService door = door(RestClient.builder(), tenant("tenant-raw", t -> {
            t.setAiRawExposure(true);
            t.setWorkerAiProvider("openai-compatible");
            t.setWorkerAiBaseUrl(echoing.url());
            t.setWorkerAiApiKey("raw-key");
            t.setWorkerAiModel("gpt-worker");
        }));
        Logger logger = (Logger) LoggerFactory.getLogger(ModelDoorService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            door.openAiChat("Bearer " + workerKey("tenant-raw"),
                    "{\"model\":\"gpt-worker\",\"messages\":[{\"role\":\"user\",\"content\":\"" + secret + "\"}]}");
            echoing.server.stop(0); // and once more with nothing listening: the upstream-error path
            door.openAiChat("Bearer " + workerKey("tenant-raw"),
                    "{\"model\":\"gpt-worker\",\"messages\":[{\"role\":\"user\",\"content\":\"" + secret + "\"}]}");
        } finally {
            logger.detachAppender(logs);
        }
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains(secret) || m.contains("invoice"));
        assertThat(ledger()).extracting(AiAudit::getResponse).noneMatch(r -> r != null && r.contains(secret));
    }

    private List<AiAudit> ledger() {
        ArgumentCaptor<AiAudit> rows = ArgumentCaptor.forClass(AiAudit.class);
        verify(audits, atLeastOnce()).save(rows.capture());
        return rows.getAllValues();
    }
}
