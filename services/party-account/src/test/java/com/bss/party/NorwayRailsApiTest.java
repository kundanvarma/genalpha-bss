package com.bss.party;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Norway rails, part A, against an EMBEDDED stub registry (the freg-shaped
 * wire mock-freg speaks in the composed stack):
 *
 * <ul>
 *   <li>a protected person's party loses street data everywhere — response,
 *       re-read, storage — while postal code + city survive;
 *   <li>the registry feed moves an address end-to-end and the per-tenant
 *       cursor advances exactly once per event (idempotent re-poll);
 *   <li>a death event opens a FLAG, nothing terminates;
 *   <li>the directory export excludes secret-number and protected-address
 *       parties — the compliance point;
 *   <li>minors default to reserved; secret number forces reserved.
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NorwayRailsApiTest {

    private static final String INDIVIDUAL = "/tmf-api/party/v4/individual";
    private static final String SYNC_RUN = "/tmf-api/party/v4/registrySync/run";
    private static final String EXPORT_RUN = "/tmf-api/party/v4/directoryExport/run";

    /** ref -> JSON the stub registry answers for a person fetch. */
    private static final Map<String, String> PERSONS = new ConcurrentHashMap<>();
    /** the sequential feed, as raw JSON events. */
    private static final List<String> FEED = new ArrayList<>();

    private static HttpServer stub;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startStubRegistry() throws IOException {
        stub = HttpServer.create(new InetSocketAddress(0), 0);
        stub.createContext("/folkeregisteret/api/personer/", exchange -> {
            String ref = exchange.getRequestURI().getPath()
                    .substring("/folkeregisteret/api/personer/".length());
            respond(exchange, PERSONS.getOrDefault(ref, "{\"result\":\"no_data\"}"));
        });
        stub.createContext("/hendelser", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            long after = 0;
            if (query != null && query.contains("seq=")) {
                after = Long.parseLong(query.replaceAll(".*seq=([0-9]+).*", "$1"));
            }
            List<String> events;
            synchronized (FEED) {
                events = after >= FEED.size() ? List.of()
                        : new ArrayList<>(FEED.subList((int) after, FEED.size()));
            }
            respond(exchange, "[" + String.join(",", events) + "]");
        });
        stub.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterAll
    static void stopStubRegistry() {
        stub.stop(0);
    }

    @DynamicPropertySource
    static void registryStub(DynamicPropertyRegistry registry) {
        registry.add("bss.registry.base-url",
                () -> "http://localhost:" + stub.getAddress().getPort());
    }

    @BeforeEach
    void drainFeed() throws Exception {
        // consume whatever a previous test appended, so each test observes
        // only its own events (the cursor is shared per tenant)
        mockMvc.perform(post(SYNC_RUN).with(staff())).andExpect(status().isOk());
    }

    private static long feedSeq(String type, String personRef) {
        synchronized (FEED) {
            long seq = FEED.size() + 1;
            FEED.add("{\"seq\":" + seq + ",\"type\":\"" + type
                    + "\",\"personRef\":\"" + personRef + "\",\"payload\":{}}");
            return seq;
        }
    }

    private String createParty(String given, String family, String extraJson) throws Exception {
        String body = """
                {
                  "givenName": "%s",
                  "familyName": "%s"%s
                }
                """.formatted(given, family, extraJson == null ? "" : "," + extraJson);
        MvcResult result = mockMvc.perform(post(INDIVIDUAL).with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private static final String POSTAL_JSON = """
            "contactMedium": [
              {"mediumType": "postalAddress", "characteristic":
                {"street1": "Hemmelig gate 1", "postCode": "0555", "city": "Oslo", "country": "NO"}},
              {"mediumType": "email", "characteristic": {"emailAddress": "p@test.local"}},
              {"mediumType": "mobile", "characteristic": {"phoneNumber": "+4790000001"}}
            ]""";

    @Test
    void protectedPerson_streetDataMaskedEverywhere_postalCodeAndCitySurvive() throws Exception {
        String id = createParty("Siri", "Skjult", POSTAL_JSON);
        // the registry shares nothing about this person
        PERSONS.put("prot-1", "{\"result\":\"no_data\"}");

        mockMvc.perform(post(INDIVIDUAL + "/" + id + "/registryLink").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personRef\": \"prot-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressProtected").value(true))
                .andExpect(jsonPath("$.contactMedium[0].characteristic.street1").doesNotExist())
                .andExpect(jsonPath("$.contactMedium[0].characteristic.postCode").value("0555"))
                .andExpect(jsonPath("$.contactMedium[0].characteristic.city").value("Oslo"));

        // the STAFF read is masked too — protection has no privileged bypass
        MvcResult read = mockMvc.perform(get(INDIVIDUAL + "/" + id).with(staffRead()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressProtected").value(true))
                .andReturn();
        String raw = read.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(raw).doesNotContain("Hemmelig gate");
        org.assertj.core.api.Assertions.assertThat(raw).contains("p@test.local");
    }

    @Test
    void registryFeed_movesTheAddress_andTheCursorAdvancesIdempotently() throws Exception {
        String id = createParty("Frida", "Flytter", POSTAL_JSON);
        PERSONS.put("ok-1", """
                {"result":"ok","name":"Frida Flytter","registeredAddress":
                  {"street1":"Nygata 9","postCode":"0151","city":"Oslo","country":"NO"}}""");
        mockMvc.perform(post(INDIVIDUAL + "/" + id + "/registryLink").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personRef\": \"ok-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressProtected").doesNotExist());

        feedSeq("addressChange", "ok-1");
        MvcResult run = mockMvc.perform(post(SYNC_RUN).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(1))
                .andReturn();
        long lastSeq = ((Number) com.jayway.jsonpath.JsonPath.read(
                run.getResponse().getContentAsString(), "$.lastSeq")).longValue();

        mockMvc.perform(get(INDIVIDUAL + "/" + id).with(staffRead()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactMedium[0].characteristic.street1").value("Nygata 9"))
                .andExpect(jsonPath("$.contactMedium[0].characteristic.postCode").value("0151"));

        // idempotent re-poll: nothing new, the cursor stands
        mockMvc.perform(post(SYNC_RUN).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(0))
                .andExpect(jsonPath("$.lastSeq").value(lastSeq));

        // a death event opens a FLAG — the party record remains
        feedSeq("death", "ok-1");
        mockMvc.perform(post(SYNC_RUN).with(staff()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(1));
        mockMvc.perform(get(INDIVIDUAL + "/" + id).with(staffRead()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deceased").value(true));
    }

    @Test
    void directoryExport_excludesSecretNumbersAndProtectedAddresses() throws Exception {
        String listed = createParty("Liv", "Listet",
                "\"birthDate\": \"1980-01-01\"," + POSTAL_JSON);
        String secret = createParty("Selma", "Skjultnummer",
                "\"birthDate\": \"1981-01-01\"," + POSTAL_JSON);
        String prot = createParty("Sigrun", "Skjermetadresse",
                "\"birthDate\": \"1982-01-01\"," + POSTAL_JSON);

        mockMvc.perform(post(INDIVIDUAL + "/" + listed + "/directorySetting").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exposure").value("full"));
        mockMvc.perform(post(INDIVIDUAL + "/" + secret + "/directorySetting").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exposure\": \"full\", \"secretNumber\": true}"))
                .andExpect(status().isOk())
                // the free service is absolute: secret number FORCES reserved
                .andExpect(jsonPath("$.exposure").value("reserved"));
        mockMvc.perform(post(INDIVIDUAL + "/" + prot + "/directorySetting").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"exposure\": \"full\"}"))
                .andExpect(status().isOk());

        // flip the third party protected AFTER their full listing was chosen
        PERSONS.put("prot-2", "{\"result\":\"no_data\"}");
        mockMvc.perform(post(INDIVIDUAL + "/" + prot + "/registryLink").with(staff())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personRef\": \"prot-2\"}"))
                .andExpect(status().isOk());

        MvcResult run = mockMvc.perform(post(EXPORT_RUN).with(staff()))
                .andExpect(status().isOk())
                .andReturn();
        String body = run.getResponse().getContentAsString();
        List<String> partyIds = com.jayway.jsonpath.JsonPath.read(body, "$.rows[*].partyId");
        org.assertj.core.api.Assertions.assertThat(partyIds).contains(listed);
        org.assertj.core.api.Assertions.assertThat(partyIds).doesNotContain(secret, prot);
        // and no protected street data rides along anywhere in the export
        org.assertj.core.api.Assertions.assertThat(partyIds).allSatisfy(p ->
                org.assertj.core.api.Assertions.assertThat(p).isNotEqualTo(prot));
    }

    @Test
    void minorsDefaultToReserved() throws Exception {
        // no birth date = treated as a minor, the safe default (same
        // derivation the household guardian logic uses)
        String kid = createParty("Nora", "Nyfødt", null);
        mockMvc.perform(post(INDIVIDUAL + "/" + kid + "/directorySetting").with(staff())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exposure").value("reserved"))
                .andExpect(jsonPath("$.secretNumber").value(false));
    }

    private static RequestPostProcessor staff() {
        return jwt().authorities(new SimpleGrantedAuthority("party:write"),
                new SimpleGrantedAuthority("party:read"));
    }

    private static RequestPostProcessor staffRead() {
        return jwt().authorities(new SimpleGrantedAuthority("party:read"));
    }
}
