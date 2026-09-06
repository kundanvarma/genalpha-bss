package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declares a slice-guarantee shortfall as a service problem, once per line per window. */
@Component
public class RestAssuranceClient implements AssuranceClient {

    private static final Logger log = LoggerFactory.getLogger(RestAssuranceClient.class);

    private final RestClient restClient;
    private final boolean enabled;
    /** (tenant|service|window-hour) already reported — the sweep runs every 20s, the window is 15 min. */
    private final Set<String> reported = java.util.Collections.synchronizedSet(new HashSet<>());

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
        String key = tenantId + "|" + serviceId + "|" + slot;
        if (!reported.add(key)) {
            return;
        }
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
}
