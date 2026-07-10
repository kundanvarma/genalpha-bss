package com.bss.gateway.tenant;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One storefront build serves every tenant; this script tells it which
 * tenant's IdP to log in against, decided by the hostname the visitor came
 * in on. Controller mappings outrank gateway routes, so this shadows the
 * static /shop/** route for exactly this one path.
 */
@RestController
public class StorefrontTenantConfigController {

    private final TenantHosts tenants;

    public StorefrontTenantConfigController(TenantHosts tenants) {
        this.tenants = tenants;
    }

    @GetMapping(value = "/shop/tenant-config.js", produces = "application/javascript")
    public ResponseEntity<String> storefrontConfig(ServerHttpRequest request) {
        TenantHosts.Entry tenant = tenants.byHost(request.getURI().getHost());
        if (tenant == null) {
            tenant = tenants.byId(tenants.getDefaultTenant());
        }
        String issuer = tenant != null && tenant.getIssuer() != null ? tenant.getIssuer() : "";
        String body = issuer.isEmpty()
                ? "// default tenant: the storefront's built-in issuer applies\n"
                : "window.BSS_STOREFRONT_CONFIG = { issuer: '" + issuer + "' };\n";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .header("Cache-Control", "no-store")
                .body(body);
    }
}
