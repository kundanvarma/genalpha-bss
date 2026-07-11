package com.bss.som.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Asks the porting service whether this customer brought a number in. If they
 * did (a completed port-in), activation uses THAT number instead of drawing a
 * fresh one from the pool — the customer keeps their number. Fail-soft: if
 * porting is not deployed or unreachable, activation falls back to the pool.
 */
@Component
public class PortingClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public PortingClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            ObjectMapper objectMapper,
            @Value("${bss.porting.base-url:http://localhost:8112}") String baseUrl,
            @Value("${bss.porting.enabled:true}") boolean enabled) {
        this.client = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /** @return the ported-in number for this party, or null to draw from the pool. */
    public String portedNumberFor(String partyId) {
        if (!enabled || partyId == null) {
            return null;
        }
        try {
            String body = client.get()
                    .uri("/tmf-api/numberPortingManagement/v1/portedNumber?relatedPartyId=" + partyId)
                    .retrieve().body(String.class);
            Map<?, ?> map = objectMapper.readValue(body, Map.class);
            return map.get("phoneNumber") == null ? null : String.valueOf(map.get("phoneNumber"));
        } catch (Exception e) {
            return null; // fail-soft to the pool
        }
    }
}
