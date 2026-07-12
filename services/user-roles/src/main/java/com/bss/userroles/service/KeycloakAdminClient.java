package com.bss.userroles.service;

import com.bss.userroles.exception.BadRequestException;
import com.bss.userroles.exception.NotFoundException;
import com.bss.userroles.security.TenantRegistry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keycloak realm-admin implementation: authenticates as the tenant's
 * bss-user-admin service account (client credentials, cached per tenant)
 * and works the realm's users and role mappings.
 */
@Component
public class KeycloakAdminClient implements IdpAdminClient {

    private record CachedToken(String value, Instant expiresAt) {
    }

    private final TenantRegistry tenants;
    private final RestClient rest;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    public KeycloakAdminClient(TenantRegistry tenants, RestClient.Builder builder) {
        this.tenants = tenants;
        this.rest = builder.build();
    }

    @Override
    public List<Map<String, Object>> realmRoles(String tenantId) {
        return getList(tenantId, "/roles");
    }

    @Override
    public List<Map<String, Object>> users(String tenantId, String username) {
        String query = username == null || username.isBlank() ? "?max=100"
                : "?exact=false&username=" + username;
        return getList(tenantId, "/users" + query);
    }

    @Override
    public List<Map<String, Object>> userRoles(String tenantId, String userId) {
        try {
            return getList(tenantId, "/users/" + userId + "/role-mappings/realm");
        } catch (HttpClientErrorException.NotFound e) {
            throw NotFoundException.forResource("User", userId);
        }
    }

    @Override
    public String createUser(String tenantId, String email, String firstName, String lastName, String password) {
        // Realms with registrationEmailAsUsername expect username == email.
        Map<String, Object> user = Map.of(
                "username", email,
                "email", email,
                "firstName", firstName,
                "lastName", lastName,
                "enabled", true,
                "emailVerified", true,
                "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)));
        try {
            var response = rest.post().uri(adminBase(tenantId) + "/users")
                    .header("Authorization", "Bearer " + tokenFor(tenantId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve().toBodilessEntity();
            // Keycloak answers 201 with Location: .../users/<id>
            String location = response.getHeaders().getFirst("Location");
            if (location == null || location.isBlank()) {
                throw new IllegalStateException("IdP did not return the new user's location");
            }
            return location.substring(location.lastIndexOf('/') + 1);
        } catch (HttpClientErrorException.Conflict e) {
            throw new BadRequestException("a login for '" + email + "' already exists");
        }
    }

    @Override
    public void grant(String tenantId, String userId, String roleName) {
        rest.post().uri(adminBase(tenantId) + "/users/" + userId + "/role-mappings/realm")
                .header("Authorization", "Bearer " + tokenFor(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(roleByName(tenantId, roleName)))
                .retrieve().toBodilessEntity();
    }

    @Override
    public void revoke(String tenantId, String userId, String roleName) {
        rest.method(org.springframework.http.HttpMethod.DELETE)
                .uri(adminBase(tenantId) + "/users/" + userId + "/role-mappings/realm")
                .header("Authorization", "Bearer " + tokenFor(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(roleByName(tenantId, roleName)))
                .retrieve().toBodilessEntity();
    }

    private Map<String, Object> roleByName(String tenantId, String roleName) {
        return realmRoles(tenantId).stream()
                .filter(r -> roleName.equals(r.get("name")))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("no such role: '" + roleName + "'"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String tenantId, String path) {
        return rest.get().uri(adminBase(tenantId) + path)
                .header("Authorization", "Bearer " + tokenFor(tenantId))
                .retrieve().body(List.class);
    }

    private String adminBase(String tenantId) {
        TenantRegistry.TenantEntry tenant = requireTenant(tenantId);
        // token-uri is <base>/realms/<realm>/protocol/openid-connect/token;
        // the admin API lives at <base>/admin/realms/<realm>.
        String tokenUri = tenant.getTokenUri();
        int realmsAt = tokenUri.indexOf("/realms/");
        String base = tokenUri.substring(0, realmsAt);
        String realm = tokenUri.substring(realmsAt + "/realms/".length(),
                tokenUri.indexOf("/protocol/"));
        return base + "/admin/realms/" + realm;
    }

    private String tokenFor(String tenantId) {
        CachedToken cached = tokens.get(tenantId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.value();
        }
        TenantRegistry.TenantEntry tenant = requireTenant(tenantId);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", tenant.getMachineClientId());
        form.add("client_secret", tenant.getMachineClientSecret());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = rest.post().uri(tenant.getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("admin token grant failed for tenant '" + tenantId + "'");
        }
        long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60;
        CachedToken token = new CachedToken(String.valueOf(response.get("access_token")),
                Instant.now().plusSeconds(Math.max(expiresIn - 30, 10)));
        tokens.put(tenantId, token);
        return token.value();
    }

    private TenantRegistry.TenantEntry requireTenant(String tenantId) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || tenant.getTokenUri() == null || tenant.getTokenUri().isBlank()) {
            throw new IllegalStateException("no IdP admin access configured for tenant '" + tenantId + "'");
        }
        return tenant;
    }
}
