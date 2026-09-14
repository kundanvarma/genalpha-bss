package com.bss.som.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/** The ACS over HTTP; a blank base URL means "no equipment seam on this deployment". */
@Component
public class RestCpeClient implements CpeClient {

    private static final Logger log = LoggerFactory.getLogger(RestCpeClient.class);

    private final RestClient restClient;
    private final boolean enabled;

    public RestCpeClient(RestClient.Builder builder, @Value("${bss.downstream.cpe-base-url:}") String baseUrl) {
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.restClient = enabled ? builder.clone().baseUrl(baseUrl).build() : null;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public Optional<CpeState> state(String tenantId, String serviceId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            JsonNode n = restClient.get().uri("/cpe/{id}", serviceId).header("X-Tenant-Id", tenantId)
                    .retrieve().body(JsonNode.class);
            if (n == null || n.path("state").isMissingNode()) {
                return Optional.empty();
            }
            return Optional.of(new CpeState(n.path("state").asText("unknown"), n.path("uptimeSeconds").asLong(0),
                    n.path("firmware").asText(""), n.path("firmwareOutdated").asBoolean(false), n.path("wifiClients").asInt(0),
                    n.path("model").asText(""), n.path("serial").asText(""), n.path("lastSeen").asText("")));
        } catch (RuntimeException e) {
            log.warn("ACS did not answer for service {}: {}", serviceId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean reboot(String tenantId, String serviceId) {
        if (!enabled) {
            return false;
        }
        try {
            restClient.post().uri("/cpe/{id}/reboot", serviceId).header("X-Tenant-Id", tenantId)
                    .header("Content-Type", "application/json").body(Map.of()).retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.warn("ACS refused the reboot for service {}: {}", serviceId, e.getMessage());
            return false;
        }
    }
}
