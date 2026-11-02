package com.bss.ontology.security;

import com.bss.ontology.api.ApiConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.OAuth2ProtectedResourceMetadata;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The OAuth 2.0 Protected Resource Metadata (RFC 9728) of the ontology's MCP
 * endpoint: an MCP client that has no token yet reads this to learn which
 * authorization server issues one. The answer is the TENANT's Keycloak issuer —
 * the same registry entry that validates the token afterwards — so a client of
 * one operator is never sent to another operator's login.
 *
 * The tenant comes from {@link TenantScope}: anonymous here, so the gateway's
 * hostname stamp (X-Tenant-Id, which the gateway strips from inbound traffic),
 * else the deployment default. The resource URL echoes the origin the client
 * came through (the gateway's X-Forwarded-Proto/Host); it decides nothing — a
 * caller who forges it only misleads itself, and the token it obtains is still
 * validated against the tenant's issuer.
 */
@Component
public class ProtectedResourceMetadata {

    public static final String MCP_PATH = ApiConstants.BASE_PATH + "/mcp";
    public static final String DOCUMENT = "/.well-known/oauth-protected-resource";

    private final TenantScope tenantScope;
    private final TenantRegistry tenants;

    public ProtectedResourceMetadata(TenantScope tenantScope, TenantRegistry tenants) {
        this.tenantScope = tenantScope;
        this.tenants = tenants;
    }

    /** Fill Spring Security's metadata document for the current request. */
    public void describe(OAuth2ProtectedResourceMetadata.Builder b) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantScope.currentTenantId());
        b.resourceName("GenAlpha ontology MCP");
        b.authorizationServers(servers -> {
            servers.clear();
            if (tenant != null && tenant.getIssuer() != null && !tenant.getIssuer().isBlank()) {
                servers.add(tenant.getIssuer());
            }
        });
        b.bearerMethods(methods -> {
            methods.clear();
            methods.add("header");
        });
        HttpServletRequest request = currentRequest();
        if (request != null) {
            b.resource(origin(request) + MCP_PATH);
        }
    }

    /**
     * The 401 challenge's {@code resource_metadata} pointer. Spring's default builds it
     * from this pod's own URL, which a client behind the gateway cannot reach; this
     * names the public origin and the one document the gateway routes here — the MCP
     * endpoint's. The rest of the registry's API is the same resource server with the
     * same issuer, so its challenge points there too.
     */
    public AuthenticationEntryPoint entryPoint() {
        BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
        entryPoint.setResourceMetadataParameterResolver(request -> origin(request) + DOCUMENT + MCP_PATH);
        return entryPoint;
    }

    static String origin(HttpServletRequest request) {
        String proto = first(request.getHeader("X-Forwarded-Proto"));
        String host = first(request.getHeader("X-Forwarded-Host"));
        if (host == null) {
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(request.getScheme()) && port == 80) || ("https".equals(request.getScheme()) && port == 443);
            host = request.getServerName() + (defaultPort ? "" : ":" + port);
        }
        return (proto == null ? request.getScheme() : proto) + "://" + host;
    }

    private static String first(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String v = header.split(",")[0].trim();
        return v.isEmpty() ? null : v;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }
}
