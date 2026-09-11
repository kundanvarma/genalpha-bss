package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The generic {@code http} OCS adapter: the plain subscriber/rate-plan REST
 * shape the bundled mock-ocs exposes in dev and a vendor's integration
 * gateway (Ericsson/Huawei/Matrixx front doors) exposes in production. Which
 * OCS a tenant points at comes from {@link OcsSettings}; a blank base URL =
 * no online charging in this deployment, every call a logged no-op.
 */
@Component
public class RestOcsProvisioningClient implements OcsProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(RestOcsProvisioningClient.class);

    private final RestClient.Builder builder;
    private final OcsSettings settings;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public RestOcsProvisioningClient(RestClient.Builder builder, OcsSettings settings) {
        this.builder = builder;
        this.settings = settings;
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public boolean enabledFor(String tenantId) {
        return settings.forTenant(tenantId).enabled();
    }

    private RestClient client(String tenantId) {
        OcsSettings.Binding b = settings.forTenant(tenantId);
        if (!b.enabled()) {
            return null;
        }
        return clients.computeIfAbsent(b.baseUrl(), url -> builder.clone().baseUrl(url).build());
    }

    @Override
    public void provision(String tenantId, String partyId, String serviceId, String chargingSpecId) {
        provision(tenantId, partyId, serviceId, chargingSpecId, List.of());
    }

    @Override
    public void provision(String tenantId, String partyId, String serviceId, String chargingSpecId,
            List<String> zeroRatedApps) {
        RestClient restClient = client(tenantId);
        if (restClient == null) {
            return;
        }
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("partyId", partyId);
            body.put("serviceId", serviceId);
            body.put("ratePlanId", chargingSpecId);
            if (zeroRatedApps != null && !zeroRatedApps.isEmpty()) {
                body.put("zeroRatedApps", zeroRatedApps);
            }
            restClient.post().uri("/subscribers")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve().toBodilessEntity();
            log.info("OCS: subscriber provisioned for service {} on rate plan {}{}", serviceId, chargingSpecId,
                    zeroRatedApps == null || zeroRatedApps.isEmpty() ? "" : " zero-rating " + zeroRatedApps);
        } catch (RuntimeException e) {
            // fail open: charging reconciliation is an ops process, activation is not
            log.warn("OCS provisioning failed for service {} ({}) — activation proceeds, reconcile later",
                    serviceId, e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void changeRatePlan(String tenantId, String serviceId, String chargingSpecId) {
        RestClient restClient = client(tenantId);
        if (restClient == null) {
            return;
        }
        try {
            Map<String, Object> sub = subscriber(restClient, tenantId, serviceId);
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

    @Override
    public void suspend(String tenantId, String serviceId) {
        setChargingState(tenantId, serviceId, "suspend");
    }

    @Override
    public void resume(String tenantId, String serviceId) {
        setChargingState(tenantId, serviceId, "resume");
    }

    @Override
    public void transfer(String tenantId, String serviceId, String newPartyId) {
        RestClient restClient = client(tenantId);
        if (restClient == null) {
            return;
        }
        try {
            Map<String, Object> sub = subscriber(restClient, tenantId, serviceId);
            if (sub == null) {
                log.warn("OCS: no subscriber for service {} — transfer not mirrored", serviceId);
                return;
            }
            restClient.patch().uri("/subscribers/{id}", String.valueOf(sub.get("id")))
                    .header("Content-Type", "application/json")
                    .body(Map.of("partyId", newPartyId))
                    .retrieve().toBodilessEntity();
            log.info("OCS: service {} now charges to party {}", serviceId, newPartyId);
        } catch (RuntimeException e) {
            log.warn("OCS transfer failed for service {} ({}) — reconcile later",
                    serviceId, e.getMessage());
        }
    }

    private void setChargingState(String tenantId, String serviceId, String action) {
        RestClient restClient = client(tenantId);
        if (restClient == null) {
            return;
        }
        try {
            Map<String, Object> sub = subscriber(restClient, tenantId, serviceId);
            if (sub == null) {
                log.warn("OCS: no subscriber for service {} — {} not mirrored", serviceId, action);
                return;
            }
            restClient.post().uri("/subscribers/{id}/" + action, String.valueOf(sub.get("id")))
                    .retrieve().toBodilessEntity();
            log.info("OCS: charging {} for service {}", action + "ed", serviceId);
        } catch (RuntimeException e) {
            log.warn("OCS {} failed for service {} ({}) — reconcile later",
                    action, serviceId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> subscriber(RestClient restClient, String tenantId, String serviceId) {
        List<Map<String, Object>> subs = restClient.get()
                .uri("/subscribers?tenantId={t}", tenantId)
                .retrieve().body(List.class);
        return subs == null ? null : subs.stream()
                .filter(s -> serviceId.equals(String.valueOf(s.get("serviceId"))))
                .findFirst().orElse(null);
    }
}
