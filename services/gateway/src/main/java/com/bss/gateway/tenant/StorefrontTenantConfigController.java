package com.bss.gateway.tenant;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * One build of each channel serves every tenant; this script tells the app
 * which tenant's IdP to log in against, decided by the hostname the visitor
 * came in on. Controller mappings outrank gateway routes, so this shadows
 * the static channel routes for exactly these paths.
 */
@RestController
public class StorefrontTenantConfigController {

    private static final Map<String, String> CHANNEL_GLOBALS = Map.of(
            "shop", "BSS_STOREFRONT_CONFIG",
            "csr", "BSS_CSR_CONFIG",
            "console", "BSS_CONSOLE_CONFIG");

    private final TenantHosts tenants;

    public StorefrontTenantConfigController(TenantHosts tenants) {
        this.tenants = tenants;
    }

    @GetMapping(value = "/{channel}/tenant-config.js", produces = "application/javascript")
    public ResponseEntity<String> channelConfig(@PathVariable String channel, ServerHttpRequest request) {
        String global = CHANNEL_GLOBALS.get(channel);
        if (global == null) {
            return ResponseEntity.notFound().build();
        }
        TenantHosts.Entry tenant = tenants.byHost(request.getURI().getHost());
        if (tenant == null) {
            tenant = tenants.byId(tenants.getDefaultTenant());
        }
        String issuer = tenant != null && tenant.getIssuer() != null ? tenant.getIssuer() : "";
        String body = issuer.isEmpty()
                ? "// default tenant: the channel's built-in issuer applies\n"
                : "window." + global + " = { issuer: '" + issuer + "' };\n";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .header("Cache-Control", "no-store")
                .body(body);
    }
}
