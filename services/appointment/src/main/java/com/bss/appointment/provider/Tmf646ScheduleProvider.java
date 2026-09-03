package com.bss.appointment.provider;

import com.bss.appointment.exception.ConflictException;
import com.bss.appointment.exception.ProviderUnavailableException;
import com.bss.appointment.schedule.ScheduleConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tenant's OWN workforce-management system answers, over TMF646 — the
 * same Open API this component exposes, so the two sides speak one language:
 * POST {providerUrl}/searchTimeSlot with relatedPlace/relatedEntity/
 * requestedTimeSlot, POST {providerUrl}/appointment to book, PATCH to cancel.
 * The credential is an ENV VAR named by providerSecretRef (the carrier/PSP
 * convention); providerCategory rides as TMF646 `category` so the FSM can
 * pick its capacity category / work-skill group. Failures surface as 502 —
 * the BSS never invents a calendar it does not own.
 */
@Component
public class Tmf646ScheduleProvider implements ScheduleProvider {

    public static final String KEY = "tmf646";
    private static final Logger log = LoggerFactory.getLogger(Tmf646ScheduleProvider.class);

    private final RestClient.Builder builder;
    private final ObjectMapper mapper;

    public Tmf646ScheduleProvider(RestClient.Builder builder, ObjectMapper mapper) {
        this.builder = builder;
        this.mapper = mapper;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public List<Window> search(ScheduleConfig cfg, SlotRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@type", "SearchTimeSlot");
        if (request.relatedPlace() != null) {
            body.put("relatedPlace", request.relatedPlace());
        }
        if (request.relatedParty() != null) {
            body.put("relatedParty", request.relatedParty());
        }
        if (request.relatedEntity() != null && !request.relatedEntity().isEmpty()) {
            body.put("relatedEntity", request.relatedEntity());
        }
        if (request.requestedTimeSlot() != null && !request.requestedTimeSlot().isEmpty()) {
            body.put("requestedTimeSlot", request.requestedTimeSlot());
        }
        if (cfg.getProviderCategory() != null) {
            body.put("category", cfg.getProviderCategory());
        }
        JsonNode root = call(cfg, () -> client(cfg).post().uri("/searchTimeSlot")
                .header("Content-Type", "application/json").body(body)
                .retrieve().body(String.class), "searchTimeSlot");
        List<Window> out = new ArrayList<>();
        JsonNode slots = root.path("availableTimeSlot");
        if (slots.isArray()) {
            for (JsonNode s : slots) {
                JsonNode vf = s.path("validFor");
                if (vf.hasNonNull("startDateTime") && vf.hasNonNull("endDateTime")) {
                    long remaining = s.hasNonNull("remaining") ? s.get("remaining").asLong() : 1;
                    out.add(new Window(OffsetDateTime.parse(vf.get("startDateTime").asText()),
                            OffsetDateTime.parse(vf.get("endDateTime").asText()), remaining));
                }
            }
        }
        return out;
    }

    @Override
    public Booking book(ScheduleConfig cfg, BookingRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("@type", "Appointment");
        body.put("validFor", Map.of("startDateTime", request.start().toString(),
                "endDateTime", request.end().toString()));
        if (request.description() != null) {
            body.put("description", request.description());
        }
        if (cfg.getProviderCategory() != null) {
            body.put("category", cfg.getProviderCategory());
        }
        if (request.relatedPlace() != null) {
            body.put("relatedPlace", request.relatedPlace());
        }
        if (request.relatedEntity() != null) {
            body.put("relatedEntity", request.relatedEntity());
        }
        if (request.partyId() != null) {
            body.put("relatedParty", List.of(Map.of("id", request.partyId(), "role", "customer",
                    "@referredType", "Individual")));
        }
        body.put("externalId", "bss:" + request.tenantId());
        JsonNode root = call(cfg, () -> client(cfg).post().uri("/appointment")
                .header("Content-Type", "application/json").body(body)
                .retrieve().body(String.class), "appointment");
        String id = root.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new ProviderUnavailableException("scheduling provider booked without returning an appointment id");
        }
        log.info("tmf646 provider booked {} for tenant {}", id, request.tenantId());
        return new Booking(id);
    }

    @Override
    public void cancel(ScheduleConfig cfg, String externalId) {
        if (externalId == null) {
            return;
        }
        call(cfg, () -> client(cfg).patch().uri("/appointment/{id}", externalId)
                .header("Content-Type", "application/json").body(Map.of("status", "cancelled"))
                .retrieve().body(String.class), "cancel");
    }

    @Override
    public Probe probe(ScheduleConfig cfg) {
        if (cfg.getProviderUrl() == null || cfg.getProviderUrl().isBlank()) {
            return new Probe(false, "no provider URL configured");
        }
        try {
            // an empty search is the cheapest TMF646 round-trip that proves auth + shape
            JsonNode root = mapper.readTree(client(cfg).post().uri("/searchTimeSlot")
                    .header("Content-Type", "application/json")
                    .body(Map.of("@type", "SearchTimeSlot"))
                    .retrieve().body(String.class));
            int n = root.path("availableTimeSlot").size();
            return new Probe(true, "reachable — " + n + " window" + (n == 1 ? "" : "s") + " offered");
        } catch (Exception e) {
            return new Probe(false, e.getMessage());
        }
    }

    private interface Call {
        String run();
    }

    private JsonNode call(ScheduleConfig cfg, Call call, String what) {
        if (cfg.getProviderUrl() == null || cfg.getProviderUrl().isBlank()) {
            throw new ProviderUnavailableException("scheduling provider 'tmf646' has no URL configured");
        }
        String resp;
        try {
            resp = call.run();
        } catch (RestClientResponseException e) {
            HttpStatusCode st = e.getStatusCode();
            if (st.value() == 409) {
                throw new ConflictException("scheduling provider: " + summary(e.getResponseBodyAsString()));
            }
            throw new ProviderUnavailableException("scheduling provider " + what + " failed: HTTP " + st.value());
        } catch (Exception e) {
            throw new ProviderUnavailableException("scheduling provider unreachable: " + e.getMessage());
        }
        try {
            return mapper.readTree(resp == null || resp.isBlank() ? "{}" : resp);
        } catch (Exception e) {
            throw new ProviderUnavailableException("scheduling provider " + what + " answered unreadably");
        }
    }

    private static String summary(String body) {
        return body == null ? "conflict" : body.replaceAll("\\s+", " ").substring(0, Math.min(160, body.length()));
    }

    private RestClient client(ScheduleConfig cfg) {
        RestClient.Builder b = builder.clone().baseUrl(cfg.getProviderUrl())
                .requestFactory(new JdkClientHttpRequestFactory());
        String key = cfg.getProviderSecretRef() == null || cfg.getProviderSecretRef().isBlank()
                ? null : System.getenv(cfg.getProviderSecretRef());
        if (key != null && !key.isBlank()) {
            b = b.defaultHeader("Authorization", "Bearer " + key);
        }
        return b.defaultHeader("X-Tenant-Id", cfg.getTenantId()).build();
    }
}
