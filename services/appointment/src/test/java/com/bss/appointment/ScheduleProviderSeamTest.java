package com.bss.appointment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The field-service seam: a tenant that names its own workforce system gets
 * THAT calendar over TMF646 — search delegates, a booking carries the external
 * id, cancel propagates, and an unreachable provider is a 502, never a faked
 * roster. A JDK HttpServer plays the TMFC046 side; tenant-a is the guinea pig.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleProviderSeamTest {

    private static final String BASE = "/tmf-api/appointment/v4";
    private static final String ISSUER_A = "https://idp.tenant-a.test/realms/bss";
    private static final String WINDOW_START = "2030-03-04T08:30-04:00";
    private static final String WINDOW_END = "2030-03-04T10:30-04:00";

    static HttpServer fsm;
    static final List<String> received = new CopyOnWriteArrayList<>();

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startFsm() throws IOException {
        fsm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fsm.createContext("/tmf646/searchTimeSlot", ex -> {
            received.add("search:" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + ":auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            reply(ex, 201, """
                    {"id":"s1","@type":"SearchTimeSlot","status":"done","availableTimeSlot":[
                      {"validFor":{"startDateTime":"%s","endDateTime":"%s"},"remaining":2}]}
                    """.formatted(WINDOW_START, WINDOW_END));
        });
        fsm.createContext("/tmf646/appointment", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("POST".equals(ex.getRequestMethod())) {
                received.add("book:" + body);
                reply(ex, 201, "{\"id\":\"FSM-42\",\"status\":\"confirmed\"}");
            } else {
                received.add(ex.getRequestMethod() + ":" + ex.getRequestURI().getPath() + ":" + body);
                reply(ex, 200, "{\"id\":\"FSM-42\",\"status\":\"cancelled\"}");
            }
        });
        fsm.start();
    }

    @AfterAll
    static void stopFsm() {
        fsm.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.issuer(ISSUER_A)).authorities(
                new SimpleGrantedAuthority("appointment:admin"),
                new SimpleGrantedAuthority("appointment:read"),
                new SimpleGrantedAuthority("appointment:write"));
    }

    private static RequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.issuer(ISSUER_A).subject(sub)).authorities(
                new SimpleGrantedAuthority("customer"),
                new SimpleGrantedAuthority("appointment:read"),
                new SimpleGrantedAuthority("appointment:write"));
    }

    private String fsmUrl() {
        return "http://127.0.0.1:" + fsm.getAddress().getPort() + "/tmf646";
    }

    @Test
    void tenantsOwnWorkforceSystemAnswersOverTmf646() throws Exception {
        // a provider needs a URL; a typo'd provider is refused, not silently defaulted
        mockMvc.perform(put(BASE + "/scheduleConfig").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"provider\": \"tmf646\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE + "/scheduleConfig").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"servicenow\", \"providerUrl\": \"http://x\"}"))
                .andExpect(status().isBadRequest());
        try {
            mockMvc.perform(put(BASE + "/scheduleConfig").with(admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"provider": "tmf646", "providerUrl": "%s", "providerCategory": "fibre-install",
                                     "providerSecretRef": "FSM_TOKEN_TEST"}
                                    """.formatted(fsmUrl())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.provider").value("tmf646"))
                    .andExpect(jsonPath("$.capacityMode").value("provider"));
            mockMvc.perform(post(BASE + "/scheduleConfig/test").with(admin()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));

            // the shop's search is answered by the FSM — its windows, with WHERE and FOR WHAT forwarded
            received.clear();
            MvcResult search = mockMvc.perform(post(BASE + "/searchTimeSlot").with(customer("cust-p1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"relatedPlace": {"role": "installation", "postCode": "4131519", "city": "Georgetown"},
                                     "relatedEntity": [{"id": "off-1", "name": "OnFiber 350", "@referredType": "ProductOffering"}]}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.provider").value("tmf646"))
                    .andExpect(jsonPath("$.availableTimeSlot[0].validFor.startDateTime").value(WINDOW_START))
                    .andExpect(jsonPath("$.availableTimeSlot[0].remaining").value(2))
                    .andReturn();
            String forwarded = received.stream().filter(r -> r.startsWith("search:")).findFirst().orElseThrow();
            if (!forwarded.contains("\"postCode\":\"4131519\"") || !forwarded.contains("OnFiber 350")
                    || !forwarded.contains("\"category\":\"fibre-install\"")) {
                throw new AssertionError("FSM did not receive place/entity/category: " + forwarded);
            }

            // booking goes to the FSM and keeps its id; our row is the customer's book of record
            MvcResult booked = mockMvc.perform(post(BASE + "/appointment").with(customer("cust-p1"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"validFor": {"startDateTime": "%s", "endDateTime": "%s"},
                                     "description": "Fibre install",
                                     "relatedEntity": [{"id": "order-9", "@referredType": "ProductOrder"}],
                                     "place": {"postCode": "4131519", "city": "Georgetown"}}
                                    """.formatted(WINDOW_START, WINDOW_END)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("confirmed"))
                    .andExpect(jsonPath("$.externalId").value("FSM-42"))
                    .andExpect(jsonPath("$.provider").value("tmf646"))
                    .andReturn();
            String id = com.jayway.jsonpath.JsonPath.read(booked.getResponse().getContentAsString(), "$.id");
            if (received.stream().noneMatch(r -> r.startsWith("book:") && r.contains("order-9"))) {
                throw new AssertionError("FSM never received the booking: " + received);
            }

            // cancel propagates to the FSM before our row flips
            mockMvc.perform(patch(BASE + "/appointment/" + id).with(customer("cust-p1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"cancelled\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
            if (received.stream().noneMatch(r -> r.startsWith("PATCH:/tmf646/appointment/FSM-42"))) {
                throw new AssertionError("FSM never received the cancel: " + received);
            }

            // the provider goes dark: honesty is a 502, not a quietly invented roster
            fsm.stop(0);
            mockMvc.perform(post(BASE + "/searchTimeSlot").with(customer("cust-p1")))
                    .andExpect(status().isBadGateway());
            mockMvc.perform(post(BASE + "/scheduleConfig/test").with(admin()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false));
        } finally {
            // back to the built-in roster so the other suites keep their flat-capacity world
            mockMvc.perform(put(BASE + "/scheduleConfig").with(admin())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"provider\": \"roster\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(get(BASE + "/scheduleConfig").with(admin()))
                    .andExpect(jsonPath("$.provider").value("roster"));
        }
    }
}
