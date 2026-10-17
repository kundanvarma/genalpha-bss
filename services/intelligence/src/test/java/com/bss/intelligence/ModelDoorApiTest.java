package com.bss.intelligence;

import com.bss.intelligence.audit.AiAudit;
import com.bss.intelligence.audit.AiAuditRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The model door, ticket 1 (#283): a worker's model call goes through the BSS
 * with the worker's OWN key, reaches the provider with the TENANT's key, and is
 * ledgered from the provider's own usage, attributed to the worker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModelDoorApiTest {

    /** A stand-in provider: answers /v1/messages with usage, remembers what it was sent. */
    private static final AtomicReference<String> SEEN_KEY = new AtomicReference<>();
    private static final AtomicReference<String> SEEN_BODY = new AtomicReference<>();
    private static final HttpServer PROVIDER = provider();

    private static HttpServer provider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", exchange -> {
                SEEN_KEY.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                SEEN_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] reply = """
                        {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5-20251001",
                         "content":[{"type":"text","text":"done"}],"stop_reason":"end_turn",
                         "usage":{"input_tokens":100,"output_tokens":20,
                                  "cache_creation_input_tokens":0,"cache_read_input_tokens":1000}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, reply.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(reply);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        PROVIDER.stop(0);
    }

    /* A list does not merge across property sources, so the default tenant is
     * declared whole here: its worker brain points at the stand-in provider. */
    @DynamicPropertySource
    static void tenant(DynamicPropertyRegistry r) {
        r.add("bss.tenants.registry[0].id", () -> "genalpha");
        r.add("bss.tenants.registry[0].issuer", () -> "https://idp.genalpha.test/realms/bss");
        r.add("bss.tenants.registry[0].worker-ai-provider", () -> "anthropic");
        r.add("bss.tenants.registry[0].worker-ai-api-key", () -> "tenant-provider-key");
        r.add("bss.tenants.registry[0].worker-ai-base-url",
                () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
        r.add("bss.tenants.registry[0].worker-ai-model", () -> "claude-haiku-4-5-20251001");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AiAuditRepository audits;

    @Autowired
    ObjectMapper json;

    private static final String CALL = """
            {"model":"claude-haiku-4-5-20251001","max_tokens":64,
             "messages":[{"role":"user","content":"close ticket 42"}]}
            """;

    private String issueKey(String badge) throws Exception {
        MvcResult issued = mockMvc.perform(post("/ai/v1/workforce/doorKeys")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ai:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"badge\":\"" + badge + "\",\"workerId\":\"w-" + badge + "\",\"job\":\"care\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").exists())
                .andReturn();
        JsonNode body = json.readTree(issued.getResponse().getContentAsString());
        return body.path("key").asString() + "|" + body.path("id").asString();
    }

    @Test
    void issuingAKeyIsAGovernanceAct() throws Exception {
        mockMvc.perform(post("/ai/v1/workforce/doorKeys")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ai:use")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"badge\":\"nobody\"}"))
                .andExpect(status().isForbidden());
        String key = issueKey("door-issue").split("\\|")[0];
        // never a prefix that would switch Hermes into Claude-login mode
        assertThat(key).startsWith("bssw_").doesNotStartWith("sk-ant-").doesNotStartWith("eyJ");
    }

    @Test
    void anUnknownKeyIsRefusedInTheProvidersOwnErrorShape() throws Exception {
        mockMvc.perform(post("/ai/v1/modelDoor/anthropic/v1/messages")
                        .header("x-api-key", "bssw_not-a-key").contentType(MediaType.APPLICATION_JSON).content(CALL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("authentication_error"));
    }

    @Test
    void aCallIsForwardedWithTheTenantsKeyAndLedgeredFromTheProvidersUsage() throws Exception {
        String key = issueKey("door-metered").split("\\|")[0];
        mockMvc.perform(post("/ai/v1/modelDoor/anthropic/v1/messages")
                        .header("x-api-key", key).contentType(MediaType.APPLICATION_JSON).content(CALL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].text").value("done"));

        // the provider saw the TENANT's key — the worker's key never left the BSS
        assertThat(SEEN_KEY.get()).isEqualTo("tenant-provider-key");
        assertThat(SEEN_BODY.get()).contains("close ticket 42");

        List<AiAudit> mine = audits.findAll().stream()
                .filter(a -> "door-metered".equals(a.getActorName())).toList();
        assertThat(mine).hasSize(1);
        AiAudit row = mine.get(0);
        assertThat(row.getActorId()).isEqualTo("w-door-metered");
        assertThat(row.getUseCase()).isEqualTo("workforce:care");
        assertThat(row.getOutcome()).isEqualTo("ok");
        // the PROVIDER's counts, not an estimate: 100 input + 1000 cache-read in, 20 out
        assertThat(row.getPromptTokens()).isEqualTo(1100);
        assertThat(row.getCompletionTokens()).isEqualTo(20);
        // haiku-4-5 rates: 100×$1 + 20×$5 + 1000×$0.10 per million = 300 micro-dollars
        assertThat(row.getCostMicros()).isEqualTo(300L);
        assertThat(row.getResourceRef()).doesNotContain("price=default");
        // no content is kept until redaction lands at the door
        assertThat(row.getPrompt()).doesNotContain("close ticket 42");
        assertThat(row.getExposure()).isEqualTo("raw");
    }

    @Test
    void aRevokedKeyStopsTheWorkerAtTheDoor() throws Exception {
        String[] issued = issueKey("door-fired").split("\\|");
        mockMvc.perform(delete("/ai/v1/workforce/doorKeys/" + issued[1])
                        .with(jwt().authorities(new SimpleGrantedAuthority("ai:admin"))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/ai/v1/modelDoor/anthropic/v1/messages")
                        .header("x-api-key", issued[0]).contentType(MediaType.APPLICATION_JSON).content(CALL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aModelWithNoListedPriceSaysSoInTheLedger() throws Exception {
        String key = issueKey("door-unpriced").split("\\|")[0];
        mockMvc.perform(post("/ai/v1/modelDoor/anthropic/v1/messages")
                        .header("x-api-key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(CALL.replace("claude-haiku-4-5-20251001", "claude-unlisted")))
                .andExpect(status().isOk());
        AiAudit row = audits.findAll().stream()
                .filter(a -> "door-unpriced".equals(a.getActorName())).findFirst().orElseThrow();
        assertThat(row.getResourceRef()).contains("price=default");
    }
}
