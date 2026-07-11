package com.bss.som.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
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

    /**
     * The number this party is keeping. Provisioning WAITS for an in-flight
     * port-in to cut over (up to a bounded window) rather than racing it and
     * drawing a fresh number — "keep your number" only means something if the
     * activation lands on the ported number. Fail-soft: no port / porting
     * unreachable → null, and the caller draws from the pool.
     */
    public String portedNumberFor(String partyId) {
        if (!enabled || partyId == null) {
            return null;
        }
        String completed = completedNumber(partyId);
        if (completed != null) {
            return completed;
        }
        // If a port-in is scheduled, hold briefly for the cutover.
        if (!hasScheduledPortIn(partyId)) {
            return null;
        }
        for (int waited = 0; waited < 15000; waited += 1500) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            String number = completedNumber(partyId);
            if (number != null) {
                return number;
            }
        }
        return null; // still pending after the window — activate on a pool number
    }

    private String completedNumber(String partyId) {
        try {
            String body = client.get()
                    .uri("/tmf-api/numberPortingManagement/v1/portedNumber?relatedPartyId=" + partyId)
                    .retrieve().body(String.class);
            Map<?, ?> map = objectMapper.readValue(body, Map.class);
            return map.get("phoneNumber") == null ? null : String.valueOf(map.get("phoneNumber"));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasScheduledPortIn(String partyId) {
        try {
            String body = client.get()
                    .uri("/tmf-api/numberPortingManagement/v1/numberPortingOrder?relatedPartyId="
                            + partyId + "&status=scheduled")
                    .retrieve().body(String.class);
            List<Map<String, Object>> list = objectMapper.readValue(body, List.class);
            return list.stream().anyMatch(o -> "portIn".equals(o.get("direction")));
        } catch (Exception e) {
            return false;
        }
    }
}
