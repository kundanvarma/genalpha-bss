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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #422 at the model door: an EU-only tenant's worker is refused a US model before anything is sent. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModelDoorResidencyTest {

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer PROVIDER = provider();

    private static HttpServer provider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                CALLS.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
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

    @DynamicPropertySource
    static void tenant(DynamicPropertyRegistry r) {
        r.add("bss.tenants.registry[0].id", () -> "genalpha");
        r.add("bss.tenants.registry[0].issuer", () -> "https://idp.genalpha.test/realms/bss");
        r.add("bss.tenants.registry[0].ai-data-residency", () -> "eu");
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

    @Test
    void anEuOnlyTenantsWorkerIsRefusedAUsModel() throws Exception {
        MvcResult issued = mockMvc.perform(post("/ai/v1/workforce/doorKeys")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ai:admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"badge\":\"door-eu\",\"workerId\":\"w-eu\",\"job\":\"care\"}"))
                .andExpect(status().isCreated()).andReturn();
        String key = json.readTree(issued.getResponse().getContentAsString()).path("key").asString();

        mockMvc.perform(post("/ai/v1/modelDoor/anthropic/v1/messages")
                        .header("x-api-key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"claude-haiku-4-5-20251001\",\"max_tokens\":8,"
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("must stay in the EU")));

        assertThat(CALLS).hasValue(0);
        List<AiAudit> mine = audits.findAll().stream().filter(a -> "door-eu".equals(a.getActorName())).toList();
        assertThat(mine).extracting(AiAudit::getOutcome).containsExactly("refused-residency");
    }
}
