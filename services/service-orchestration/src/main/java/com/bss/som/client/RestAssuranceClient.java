package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Declares a slice-guarantee shortfall as a service problem, once per line per window. */
@Component
public class RestAssuranceClient implements AssuranceClient {

    private static final Logger log = LoggerFactory.getLogger(RestAssuranceClient.class);

    private final RestClient restClient;
    private final boolean enabled;
    /** (tenant|service) → the window in which it was last confirmed open — the sweep runs every 20s, the window is 15 min. */
    private final Map<String, String> reported = new java.util.concurrent.ConcurrentHashMap<>();

    public RestAssuranceClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.assurance-base-url:}") String baseUrl) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.restClient = enabled ? builder.baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory())
                .requestInterceptor(tokenInterceptor).build() : null;
    }

    @Override
    public void reportSliceShortfall(String tenantId, String serviceId, String partyId,
            int guaranteedDlMbps, double measuredDlMbps, Integer windowMinutes) {
        if (!enabled) {
            return;
        }
        int win = windowMinutes == null || windowMinutes <= 0 ? 15 : windowMinutes;
        String slot = OffsetDateTime.now().truncatedTo(ChronoUnit.MINUTES).toString();
        slot = slot.substring(0, 14) + String.format("%02d", (OffsetDateTime.now().getMinute() / win) * win);
        // ONE open problem per line, however long the shortfall lasts: once reported, the line is
        // re-checked only once per window, and only re-raised when nobody has an open problem on it
        // any more (someone resolved it while the shortfall goes on). Never a new problem per sweep.
        String key = tenantId + "|" + serviceId;
        String seen = reported.get(key);
        if (seen != null) {
            if (seen.equals(slot)) {
                return;
            }
            if (stillOpen(tenantId, serviceId)) {
                reported.put(key, slot);
                return;
            }
        } else if (stillOpen(tenantId, serviceId)) {
            reported.put(key, slot);
            return;
        }
        reported.put(key, slot);
        try {
            Map<String, Object> problem = Map.of(
                    "name", "Priority slice below guarantee",
                    "description", String.format("Line %s: guaranteed %d Mbit/s downlink, measured %.1f Mbit/s over %d min",
                            serviceId, guaranteedDlMbps, measuredDlMbps, win),
                    "category", "network.slice.guarantee",
                    "priority", 2,
                    "affectedObject", serviceId,
                    "originatorParty", Map.of("id", "service-orchestration", "role", "serviceProvider", "@referredType", "Organization"),
                    "relatedParty", partyId == null ? List.of() : List.of(Map.of("id", partyId, "role", "customer", "@referredType", "Individual")),
                    "characteristic", List.of(
                            Map.of("name", "guaranteedDlMbps", "value", guaranteedDlMbps),
                            Map.of("name", "measuredDlMbps", "value", measuredDlMbps),
                            Map.of("name", "windowMinutes", "value", win)));
            restClient.post().uri("/tmf-api/serviceProblemManagement/v4/serviceProblem")
                    .header("Content-Type", "application/json").header("X-Tenant-Id", tenantId)
                    .body(problem).retrieve().toBodilessEntity();
            log.info("slice guarantee shortfall declared for line {}: {} < {} Mbit/s", serviceId, measuredDlMbps, guaranteedDlMbps);
        } catch (RuntimeException e) {
            reported.remove(key);
            log.warn("slice shortfall report failed for {} ({}) — retried next sweep", serviceId, e.getMessage());
        }
    }

    /** Is there an open slice problem on this line already? Asked once per window, never per sweep. */
    private boolean stillOpen(String tenantId, String serviceId) {
        try {
            List<?> open = restClient.get()
                    .uri("/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open&limit=500")
                    .header("X-Tenant-Id", tenantId).retrieve().body(List.class);
            if (open == null) {
                return false;
            }
            for (Object o : open) {
                if (o instanceof Map<?, ?> p && serviceId.equals(String.valueOf(p.get("affectedObject")))
                        && "network.slice.guarantee".equals(String.valueOf(p.get("category")))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("could not read open problems for {} ({}) — assuming none", serviceId, e.getMessage());
            return false;
        }
    }
}
