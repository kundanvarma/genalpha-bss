package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Talks to whatever slice controller the deployment points at (SLICE_BASE_URL
 * — the bundled mock-5gc in dev; the operator's PCF/slice-manager adapter in
 * production). Blank base-url = no slicing in this deployment: every call is a
 * logged no-op, so a 4G-only operator loses nothing.
 */
@Component
public class RestSliceProvisioningClient implements SliceProvisioningClient {

    private static final Logger log = LoggerFactory.getLogger(RestSliceProvisioningClient.class);

    private final RestClient restClient;
    private final boolean enabled;

    public RestSliceProvisioningClient(RestClient.Builder builder,
            @Value("${bss.downstream.slice-base-url:}") String baseUrl) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.restClient = enabled ? builder.baseUrl(baseUrl).build() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<SliceState> apply(String tenantId, String serviceId, String profile, OffsetDateTime until) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantId", tenantId);
            body.put("profile", profile);
            if (until != null) {
                body.put("until", until.toString());
            }
            Map<String, Object> resp = restClient.put().uri("/subscribers/{id}/slice", serviceId)
                    .header("Content-Type", "application/json").body(body)
                    .retrieve().body(Map.class);
            log.info("slice: service {} -> profile '{}'{}", serviceId, profile, until == null ? "" : " until " + until);
            return Optional.of(stateOf(resp));
        } catch (RuntimeException e) {
            log.warn("slice apply failed for service {} ({}) — order proceeds, the expiry sweep re-applies",
                    serviceId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void release(String tenantId, String serviceId) {
        if (!enabled) {
            return;
        }
        try {
            restClient.delete().uri("/subscribers/{id}/slice", serviceId).retrieve().toBodilessEntity();
            log.info("slice: service {} -> default", serviceId);
        } catch (RuntimeException e) {
            log.warn("slice release failed for service {} ({}) — the core reverts on its own clock", serviceId, e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<SliceState> current(String tenantId, String serviceId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            Map<String, Object> resp = restClient.get().uri("/subscribers/{id}/slice", serviceId)
                    .retrieve().body(Map.class);
            return resp == null ? Optional.empty() : Optional.of(stateOf(resp));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static SliceState stateOf(Map<String, Object> resp) {
        String profile = resp == null || resp.get("profile") == null ? "default" : String.valueOf(resp.get("profile"));
        OffsetDateTime until = resp != null && resp.get("until") != null ? OffsetDateTime.parse(String.valueOf(resp.get("until"))) : null;
        boolean active = resp != null && Boolean.TRUE.equals(resp.get("active"));
        return new SliceState(profile, until, active);
    }
}
