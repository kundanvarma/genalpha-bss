package com.bss.party.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * The national-registry seam, re-sync face: person fetch by stable ref and
 * the sequential event feed (address changes, name changes, deaths). Speaks
 * the freg-shaped wire of mock-freg in dev; a real deployment points the
 * base URL at the registry / a distributor and swaps the credential —
 * config only, exactly like the address-verification adapter. The credential
 * is read from the env var named by {@code bss.registry.secret-ref} at call
 * time, never stored.
 *
 * <p>Fail-open by shape: a registry outage returns 'unavailable' / an empty
 * feed, never an exception — the sync worker simply tries again next poll.
 */
@Component
public class RegistryClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);

    /** status: ok | protected | unavailable. A protected person (or an
     * unknown ref — the registry makes them indistinguishable BY DESIGN)
     * carries no name and no address. */
    public record PersonRecord(String status, String name, Map<String, Object> registeredAddress) {
        public static PersonRecord of(String status) {
            return new PersonRecord(status, null, null);
        }
    }

    /** One feed event: {seq, type: addressChange|nameChange|death, personRef, payload}. */
    public record FeedEvent(long seq, String type, String personRef, Map<String, Object> payload) {
    }

    private final RestClient rest;
    private final String secretRef;

    public RegistryClient(RestClient.Builder builder,
            @Value("${bss.registry.base-url:http://localhost:8141}") String baseUrl,
            @Value("${bss.registry.secret-ref:FREG_API_KEY}") String secretRef) {
        this.rest = builder.clone().baseUrl(baseUrl).build();
        this.secretRef = secretRef;
    }

    private String credential() {
        return System.getenv().getOrDefault(secretRef, "");
    }

    @SuppressWarnings("unchecked")
    public PersonRecord fetchPerson(String personRef) {
        try {
            Map<String, Object> resp = rest.get()
                    .uri("/folkeregisteret/api/personer/{ref}", personRef)
                    .header("Authorization", "Bearer " + credential())
                    .retrieve()
                    .body(Map.class);
            if (resp == null || resp.get("result") == null) {
                return PersonRecord.of("unavailable");
            }
            if (!"ok".equals(resp.get("result"))) {
                // no_data = protected marker OR nothing the register may
                // share — either way the BSS obligation is the same:
                // graceful address-absence.
                return PersonRecord.of("protected");
            }
            return new PersonRecord("ok",
                    resp.get("name") == null ? null : String.valueOf(resp.get("name")),
                    resp.get("registeredAddress") instanceof Map<?, ?> a
                            ? (Map<String, Object>) a : null);
        } catch (Exception e) {
            log.warn("registry person fetch unavailable: {}", e.getMessage());
            return PersonRecord.of("unavailable");
        }
    }

    /** All feed events after the given cursor, oldest first; empty on outage. */
    @SuppressWarnings("unchecked")
    public List<FeedEvent> fetchEvents(long afterSeq) {
        try {
            List<Map<String, Object>> resp = rest.get()
                    .uri(uri -> uri.path("/hendelser").queryParam("seq", afterSeq).build())
                    .header("Authorization", "Bearer " + credential())
                    .retrieve()
                    .body(List.class);
            if (resp == null) {
                return List.of();
            }
            return resp.stream()
                    .filter(e -> e.get("seq") instanceof Number)
                    .map(e -> new FeedEvent(
                            ((Number) e.get("seq")).longValue(),
                            String.valueOf(e.get("type")),
                            String.valueOf(e.get("personRef")),
                            e.get("payload") instanceof Map<?, ?> p
                                    ? (Map<String, Object>) p : Map.of()))
                    .sorted(java.util.Comparator.comparingLong(FeedEvent::seq))
                    .toList();
        } catch (Exception e) {
            log.debug("registry feed unavailable (will retry next poll): {}", e.getMessage());
            return List.of();
        }
    }
}
