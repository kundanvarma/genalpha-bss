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

    /** The app's tenant manifest: identity + branding, JSON-shaped. */
    @GetMapping(value = "/app/tenant-config.json")
    public ResponseEntity<Map<String, Object>> appManifest(ServerHttpRequest request) {
        TenantHosts.Entry tenant = tenants.byHost(request.getURI().getHost());
        if (tenant == null) {
            tenant = tenants.byId(tenants.getDefaultTenant());
        }
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("tenantId", tenant.getId());
        manifest.put("issuer", tenant.getIssuer());
        manifest.put("clientId", "bss-app");
        if (tenant.getBrandName() != null) manifest.put("brandName", tenant.getBrandName());
        if (tenant.getBrandColor() != null) manifest.put("brandColor", tenant.getBrandColor());
        manifest.put("logoUrl", logoUrlOf(tenant));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(manifest);
    }

    private String logoUrlOf(TenantHosts.Entry tenant) {
        return tenant != null && tenant.getLogoUrl() != null ? tenant.getLogoUrl()
                : "/tmf-api/documentManagement/v4/document/brand-logo";
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
        String body = "window." + global + " = { issuer: '" + issuer
                + "', logoUrl: '" + logoUrlOf(tenant) + "' };\n";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .header("Cache-Control", "no-store")
                .body(body);
    }
}
