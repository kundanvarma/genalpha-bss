package com.bss.gateway.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The white-label map: which hostnames belong to which tenant, and which
 * OIDC issuer that tenant's channels log in against. Anonymous traffic gets
 * its tenant from the Host header; everything authenticated is decided by
 * the token issuer inside each service, never here.
 */
@Component
@ConfigurationProperties(prefix = "bss.tenants")
public class TenantHosts {

    private String defaultTenant = "genalpha";
    private List<Entry> registry = new ArrayList<>();

    public Entry byHost(String hostname) {
        if (hostname == null) {
            return null;
        }
        return registry.stream().filter(t -> t.getHosts().contains(hostname)).findFirst().orElse(null);
    }

    public Entry byId(String tenantId) {
        return registry.stream().filter(t -> t.getId().equals(tenantId)).findFirst().orElse(null);
    }

    public String getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    public List<Entry> getRegistry() {
        return registry;
    }

    public void setRegistry(List<Entry> registry) {
        this.registry = registry;
    }

    public static class Entry {

        private String id;
        private String issuer;
        private String brandName;
        private String brandColor;
        private String locale;
        private String currency;
        /** Any URL — our TMF667 endpoint by default, a CMS CDN if the operator brings one. */
        private String logoUrl;
        private List<String> hosts = new ArrayList<>();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getBrandName() {
            return brandName;
        }

        public void setBrandName(String brandName) {
            this.brandName = brandName;
        }

        public String getBrandColor() {
            return brandColor;
        }

        public void setBrandColor(String brandColor) {
            this.brandColor = brandColor;
        }

        public String getLocale() {
            return locale;
        }

        public void setLocale(String locale) {
            this.locale = locale;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        public String getLogoUrl() {
            return logoUrl;
        }

        public void setLogoUrl(String logoUrl) {
            this.logoUrl = logoUrl;
        }

        public List<String> getHosts() {
            return hosts;
        }

        public void setHosts(List<String> hosts) {
            this.hosts = hosts;
        }
    }
}
