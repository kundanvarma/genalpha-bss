package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Talks to whatever OCS the deployment points at (OCS_BASE_URL — the
 * bundled mock-ocs in dev, the operator's Ericsson/Huawei/Matrixx adapter in
 * production). Blank base-url = no online charging in this deployment; every
 * call is a logged no-op and activation proceeds untouched.
 */
@Component
public class RestOcsProvisioningClient implements OcsProvisioningClient {

    private static final Logger log = LoggerFactory.getLogger(RestOcsProvisioningClient.class);

    private final RestClient restClient;
    private final boolean enabled;

    public RestOcsProvisioningClient(RestClient.Builder builder,
            @Value("${bss.downstream.ocs-base-url:}") String baseUrl) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.restClient = enabled ? builder.baseUrl(baseUrl).build() : null;
    }

    @Override
    public void provision(String tenantId, String partyId, String serviceId, String chargingSpecId) {
        if (!enabled) {
            return;
        }
        try {
            restClient.post().uri("/subscribers")
                    .header("Content-Type", "application/json")
                    .body(Map.of("tenantId", tenantId, "partyId", partyId,
                            "serviceId", serviceId, "ratePlanId", chargingSpecId))
                    .retrieve().toBodilessEntity();
            log.info("OCS: subscriber provisioned for service {} on rate plan {}", serviceId, chargingSpecId);
        } catch (RuntimeException e) {
            // fail open: charging reconciliation is an ops process, activation is not
            log.warn("OCS provisioning failed for service {} ({}) — activation proceeds, reconcile later",
                    serviceId, e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void changeRatePlan(String tenantId, String serviceId, String chargingSpecId) {
        if (!enabled) {
            return;
        }
        try {
            List<Map<String, Object>> subs = restClient.get()
                    .uri("/subscribers?tenantId={t}", tenantId)
                    .retrieve().body(List.class);
            Map<String, Object> sub = subs == null ? null : subs.stream()
                    .filter(s -> serviceId.equals(String.valueOf(s.get("serviceId"))))
                    .findFirst().orElse(null);
            if (sub == null) {
                log.warn("OCS: no subscriber for service {} — plan change not mirrored", serviceId);
                return;
            }
            restClient.patch().uri("/subscribers/{id}", String.valueOf(sub.get("id")))
                    .header("Content-Type", "application/json")
                    .body(Map.of("ratePlanId", chargingSpecId))
                    .retrieve().toBodilessEntity();
            log.info("OCS: service {} moved to rate plan {}", serviceId, chargingSpecId);
        } catch (RuntimeException e) {
            log.warn("OCS rate-plan change failed for service {} ({}) — reconcile later",
                    serviceId, e.getMessage());
        }
    }
}
