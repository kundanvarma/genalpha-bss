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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The model door's second adapter (#309): OpenAI Chat Completions — what OpenAI
 * and every OpenAI-compatible endpoint (a local model server, a provider's
 * compatibility endpoint) speak. Same door, same guarantees, a different API:
 * the key is a Bearer token, cached prompt tokens sit inside prompt_tokens, and a
 * tool call's arguments are a JSON string.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModelDoorOpenAiTest {

    private static final AtomicReference<String> SEEN_AUTH = new AtomicReference<>();
    private static final AtomicReference<String> SEEN_BODY = new AtomicReference<>();
    private static final HttpServer PROVIDER = provider();

    /** A stand-in OpenAI-API model that USES a placeholder it saw, in its content and a tool call. */
    private static HttpServer provider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                SEEN_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                SEEN_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Matcher seen = Pattern.compile("<[a-z]+#\\d+>").matcher(SEEN_BODY.get());
                String echo = seen.find() ? seen.group() : "nothing";
                byte[] reply = ("""
                        {"id":"chatcmpl-1","object":"chat.completion","model":"gpt-test-worker",
                         "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant",
                           "content":"done ECHO",
                           "tool_calls":[{"id":"call_1","type":"function",
                             "function":{"name":"send_mail","arguments":"{\\"to\\":\\"ECHO\\"}"}}]}}],
                         "usage":{"prompt_tokens":1100,"completion_tokens":20,
                                  "prompt_tokens_details":{"cached_tokens":1000}}}
                        """).replace("ECHO", echo).getBytes(StandardCharsets.UTF_8);
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

    private static final String ANTHROPIC_TENANT_ISSUER = "https://idp.genalpha.test/realms/bss";
    private static final String OPENAI_TENANT_ISSUER = "https://idp.tenant-a.test/realms/bss";

    /* genalpha runs an Anthropic worker brain; tenant-a an OpenAI-API one, at the stand-in */
    @DynamicPropertySource
    static void tenants(DynamicPropertyRegistry r) {
        r.add("bss.tenants.registry[0].id", () -> "genalpha");
        r.add("bss.tenants.registry[0].issuer", () -> ANTHROPIC_TENANT_ISSUER);
        r.add("bss.tenants.registry[0].worker-ai-provider", () -> "anthropic");
        r.add("bss.tenants.registry[0].worker-ai-api-key", () -> "anthropic-key");
        r.add("bss.tenants.registry[0].worker-ai-model", () -> "claude-haiku-4-5-20251001");
        r.add("bss.tenants.registry[1].id", () -> "tenant-a");
        r.add("bss.tenants.registry[1].issuer", () -> OPENAI_TENANT_ISSUER);
        r.add("bss.tenants.registry[1].worker-ai-provider", () -> "openai-compatible");
        r.add("bss.tenants.registry[1].worker-ai-api-key", () -> "tenant-openai-key");
        r.add("bss.tenants.registry[1].worker-ai-base-url",
                () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
        r.add("bss.tenants.registry[1].worker-ai-model", () -> "gpt-test-worker");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AiAuditRepository audits;

    @Autowired
    ObjectMapper json;

    private static RequestPostProcessor admin(String issuer) {
        return jwt().jwt(j -> j.issuer(issuer)).authorities(new SimpleGrantedAuthority("ai:admin"));
    }

    private String keyFor(String issuer, String badge) throws Exception {
        MvcResult issued = mockMvc.perform(post("/ai/v1/workforce/doorKeys").with(admin(issuer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"badge\":\"" + badge + "\",\"workerId\":\"w-" + badge + "\",\"job\":\"care\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(issued.getResponse().getContentAsString()).path("key").asString();
    }

    private static final String CALL = """
            {"model":"gpt-test-worker",
             "messages":[
               {"role":"system","content":"You are a care worker."},
               {"role":"user","content":"Work the queue."},
               {"role":"assistant","content":null,"tool_calls":[{"id":"call_0","type":"function",
                 "function":{"name":"care_ticket_get","arguments":"{\\"id\\":\\"t-7\\"}"}}]},
               {"role":"tool","tool_call_id":"call_0",
                "content":"Customer paula@family.example says the router is red; call +47 912 34 567."}
             ]}
            """;

    @Test
    void aBearerDoorKeyReachesTheProviderAsTheTenantsKeyAndIsMeasuredWithTheCacheApart() throws Exception {
        String key = keyFor(OPENAI_TENANT_ISSUER, "oa-metered");
        MvcResult answered = mockMvc.perform(post("/ai/v1/modelDoor/openai/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(CALL))
                .andExpect(status().isOk()).andReturn();
        // the door key is not a login token: it passed Spring Security, and the provider saw the TENANT's key
        assertThat(SEEN_AUTH.get()).isEqualTo("Bearer tenant-openai-key");

        AiAudit row = audits.findAll().stream().filter(a -> "oa-metered".equals(a.getActorName())).findFirst().orElseThrow();
        assertThat(row.getProvider()).isEqualTo("openai");
        assertThat(row.getPromptTokens()).isEqualTo(1100);       // 100 fresh + 1000 cached
        assertThat(row.getCompletionTokens()).isEqualTo(20);
        assertThat(row.getResourceRef()).contains("price=default");  // gpt-test-worker has no listed rate
        assertThat(row.getExposure()).isEqualTo("raw-redacted");

        // OUT: placeholders, never the customer's values; ids and function names untouched
        assertThat(SEEN_BODY.get()).doesNotContain("paula@family.example").doesNotContain("912 34 567")
                .contains("<email#1>").contains("\"call_0\"").contains("care_ticket_get").contains("t-7");
        // BACK: the real value in the content AND inside the tool call's argument string
        JsonNode message = json.readTree(answered.getResponse().getContentAsString())
                .path("choices").get(0).path("message");
        assertThat(message.path("content").asString()).isEqualTo("done paula@family.example");
        assertThat(json.readTree(message.path("tool_calls").get(0).path("function").path("arguments").asString())
                .path("to").asString()).isEqualTo("paula@family.example");
    }

    @Test
    void refusalsComeBackInOpenAisOwnErrorShape() throws Exception {
        mockMvc.perform(post("/ai/v1/modelDoor/openai/v1/chat/completions")
                        .header("Authorization", "Bearer bssw_not-a-key")
                        .contentType(MediaType.APPLICATION_JSON).content(CALL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
        String key = keyFor(OPENAI_TENANT_ISSUER, "oa-killed");
        try {
            mockMvc.perform(post("/ai/v1/governance/budget").with(admin(OPENAI_TENANT_ISSUER))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());
            mockMvc.perform(post("/ai/v1/modelDoor/openai/v1/chat/completions")
                            .header("Authorization", "Bearer " + key)
                            .contentType(MediaType.APPLICATION_JSON).content(CALL))
                    // 402 and insufficient_quota: the SDK's wall, not something to retry
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.error.type").value("insufficient_quota"));
        } finally {
            mockMvc.perform(post("/ai/v1/governance/budget").with(admin(OPENAI_TENANT_ISSUER))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());
        }
        mockMvc.perform(post("/ai/v1/modelDoor/openai/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(CALL.replace("{\"model\":\"gpt-test-worker\",",
                                "{\"model\":\"gpt-test-worker\",\"stream\":true,")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("not supported")));
        mockMvc.perform(get("/ai/v1/modelDoor/openai/v1/models")).andExpect(status().isNotFound());
    }

    @Test
    void aWorkerOfAnAnthropicTenantIsNotServedAtTheOpenAiDoor() throws Exception {
        String key = keyFor(ANTHROPIC_TENANT_ISSUER, "oa-wrong-door");
        SEEN_BODY.set(null);
        mockMvc.perform(post("/ai/v1/modelDoor/openai/v1/chat/completions")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(CALL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("not served")));
        assertThat(SEEN_BODY.get()).isNull();
    }

    @Test
    void aLoginTokenStillWorksEverywhereElse() throws Exception {
        // the door's Bearer exemption is the door's alone: elsewhere a Bearer token is still a login token
        mockMvc.perform(get("/ai/v1/governance").header("Authorization", "Bearer bssw_not-a-login-token"))
                .andExpect(status().isUnauthorized());
    }
}
