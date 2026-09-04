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
            "console", "BSS_CONSOLE_CONFIG",
            "biz", "BSS_BIZ_CONFIG");

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
        manifest.put("locale", tenant.getLocale() == null ? "en" : tenant.getLocale());
        manifest.put("currency", tenant.getCurrency() == null ? "EUR" : tenant.getCurrency());
        manifest.put("timezone", tenant.getTimezone() == null ? "UTC" : tenant.getTimezone());
        if (tenant.getCountry() != null) manifest.put("country", tenant.getCountry());
        if (tenant.getPriceDecimals() != null) manifest.put("priceDecimals", tenant.getPriceDecimals());
        if (tenant.getCurrencyDisplay() != null) manifest.put("currencyDisplay", tenant.getCurrencyDisplay());
        if (tenant.getPriceNote() != null) manifest.put("priceNote", tenant.getPriceNote());
        if (tenant.getSimRegistration() != null) manifest.put("simRegistration", tenant.getSimRegistration());
        if (tenant.getSupportPhone() != null) manifest.put("supportPhone", tenant.getSupportPhone());
        if (tenant.getSupportWhatsapp() != null) manifest.put("supportWhatsapp", tenant.getSupportWhatsapp());
        if (tenant.getSupportEmail() != null) manifest.put("supportEmail", tenant.getSupportEmail());
        if (tenant.getAppStoreUrl() != null) manifest.put("appStoreUrl", tenant.getAppStoreUrl());
        if (tenant.getPlayStoreUrl() != null) manifest.put("playStoreUrl", tenant.getPlayStoreUrl());
        if (tenant.getPrivacyUrl() != null) manifest.put("privacyUrl", tenant.getPrivacyUrl());
        if (tenant.getTermsUrl() != null) manifest.put("termsUrl", tenant.getTermsUrl());
        if (tenant.getTagline() != null) manifest.put("tagline", tenant.getTagline());
        // the pricing-policy ATTESTATION: uniform = one price everywhere;
        // per-channel = the tenant chose differentiated channel pricing and
        // says so openly — what is forbidden is differentiation that hides
        manifest.put("priceParity", tenant.getPriceParityMode());
        manifest.put("catalogGovernance", tenant.getCatalogGovernance());
        manifest.put("sandbox", tenant.isSandbox());
        manifest.put("logoUrl", logoUrlOf(tenant));
        manifest.put("businessSales", tenant.isBusinessSales());
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
        String brandName = tenant != null && tenant.getBrandName() != null ? tenant.getBrandName() : "";
        String brandColor = tenant != null && tenant.getBrandColor() != null ? tenant.getBrandColor() : "";
        String locale = tenant != null && tenant.getLocale() != null ? tenant.getLocale() : "en";
        String currency = tenant != null && tenant.getCurrency() != null ? tenant.getCurrency() : "EUR";
        String timezone = tenant != null && tenant.getTimezone() != null ? tenant.getTimezone() : "UTC";
        String country = tenant != null && tenant.getCountry() != null ? tenant.getCountry() : "";
        String priceDecimals = tenant != null && tenant.getPriceDecimals() != null ? String.valueOf(tenant.getPriceDecimals()) : "null";
        String currencyDisplay = tenant != null && tenant.getCurrencyDisplay() != null ? tenant.getCurrencyDisplay() : "symbol";
        String priceNote = tenant != null && tenant.getPriceNote() != null ? tenant.getPriceNote() : "";
        String simRegistration = tenant != null && tenant.getSimRegistration() != null ? tenant.getSimRegistration() : "off";
        String supportPhone = tenant != null && tenant.getSupportPhone() != null ? tenant.getSupportPhone() : "";
        String supportWhatsapp = tenant != null && tenant.getSupportWhatsapp() != null ? tenant.getSupportWhatsapp() : "";
        String supportEmail = tenant != null && tenant.getSupportEmail() != null ? tenant.getSupportEmail() : "";
        String appStoreUrl = tenant != null && tenant.getAppStoreUrl() != null ? tenant.getAppStoreUrl() : "";
        String playStoreUrl = tenant != null && tenant.getPlayStoreUrl() != null ? tenant.getPlayStoreUrl() : "";
        String privacyUrl = tenant != null && tenant.getPrivacyUrl() != null ? tenant.getPrivacyUrl() : "";
        String termsUrl = tenant != null && tenant.getTermsUrl() != null ? tenant.getTermsUrl() : "";
        boolean businessSales = tenant != null && tenant.isBusinessSales();
        String tagline = tenant != null && tenant.getTagline() != null ? tenant.getTagline() : "";
        String body = "window." + global + " = { issuer: '" + js(issuer)
                + "', logoUrl: '" + js(logoUrlOf(tenant))
                + "', brandName: '" + js(brandName)
                + "', brandColor: '" + js(brandColor)
                + "', locale: '" + js(locale)
                + "', currency: '" + js(currency)
                + "', timezone: '" + js(timezone)
                + "', country: '" + js(country)
                + "', priceDecimals: " + priceDecimals
                + ", currencyDisplay: '" + js(currencyDisplay)
                + "', priceNote: '" + js(priceNote)
                + "', simRegistration: '" + js(simRegistration)
                + "', supportPhone: '" + js(supportPhone)
                + "', supportWhatsapp: '" + js(supportWhatsapp)
                + "', supportEmail: '" + js(supportEmail)
                + "', appStoreUrl: '" + js(appStoreUrl)
                + "', playStoreUrl: '" + js(playStoreUrl)
                + "', privacyUrl: '" + js(privacyUrl)
                + "', termsUrl: '" + js(termsUrl)
                + "', tagline: '" + js(tagline)
                + "', priceParity: '" + js(tenant != null ? tenant.getPriceParityMode() : "uniform")
                + "', businessSales: " + businessSales + " };\n";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .header("Cache-Control", "no-store")
                .body(body);
    }

    /** A value inside a single-quoted JS literal — quotes and backslashes escaped. */
    private static String js(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", " ").replace("\r", " ");
    }
}
