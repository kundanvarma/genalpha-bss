package com.bss.communication.client;

import com.bss.communication.security.TenantRegistry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-credentials tokens for service-to-service calls, minted from the
 * ACTING TENANT's own IdP and cached until shortly before expiry. Exposed
 * by tenant id (not as a request interceptor) because the ESP forwarder
 * runs on async threads where no request context exists.
 */
@Component
public class MachineTokens {

    private record CachedToken(String value, Instant expiresAt) {
    }

    private final TenantRegistry tenants;
    private final RestClient tokenClient;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public MachineTokens(TenantRegistry tenants, RestClient.Builder builder) {
        this.tenants = tenants;
        this.tokenClient = builder.build();
    }

    public String tokenFor(String tenantId) {
        CachedToken cached = cache.get(tenantId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || tenant.getTokenUri() == null || tenant.getTokenUri().isBlank()) {
            throw new IllegalStateException("no machine credentials configured for tenant '" + tenantId + "'");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", tenant.getMachineClientId());
        form.add("client_secret", tenant.getMachineClientSecret());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = tokenClient.post().uri(tenant.getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("client credentials grant failed for tenant '" + tenantId + "'");
        }
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60;
        CachedToken token = new CachedToken(String.valueOf(response.get("access_token")),
                Instant.now().plusSeconds(Math.max(expiresIn - 30, 10)));
        cache.put(tenantId, token);
        return token.value();
    }
}
