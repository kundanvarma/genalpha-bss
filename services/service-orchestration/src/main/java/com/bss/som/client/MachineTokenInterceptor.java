package com.bss.som.client;

import com.bss.som.security.TenantRegistry;
import com.bss.som.security.TenantScope;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attaches a client-credentials bearer token to outgoing service-to-service
 * calls — from the ACTING TENANT's own IdP. The tenant a request is serving
 * decides which token endpoint and machine client sign the downstream call,
 * so a downstream service scopes the machine caller to the same tenant as
 * the user who triggered the work. Tokens are cached per tenant and
 * refreshed shortly before expiry.
 */
@Component
public class MachineTokenInterceptor implements ClientHttpRequestInterceptor {

    private record CachedToken(String value, Instant expiresAt) {
    }

    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final RestClient tokenClient;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public MachineTokenInterceptor(TenantRegistry tenants, TenantScope tenantScope,
            RestClient.Builder builder) {
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.tokenClient = builder.build();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(tokenFor(tenantScope.currentTenantId()));
        return execution.execute(request, body);
    }

    private String tokenFor(String tenantId) {
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
