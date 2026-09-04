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
        /** The storefront hero line — the operator's voice, console-editable. */
        private String tagline;
        /** uniform (default) | per-channel — attested to humans AND agents. */
        private String priceParityMode;
        /** direct (default) | governed — is launch a decision here? Attested. */
        private String catalogGovernance;
        private boolean sandbox;
        private String locale;
        private String currency;
        /** IANA zone the operator runs its calendar in (install windows, opening hours). */
        private String timezone;
        /** ISO-3166 country the operator sells in (address defaults, number formats, Intl locale). */
        private String country;
        /** Minor-unit digits shown on prices (Guyana: 0 — cents were withdrawn in 1992); null = currency default. */
        private Integer priceDecimals;
        /** Intl currencyDisplay: symbol | narrowSymbol | code | name. */
        private String currencyDisplay;
        /** A statutory price note shown beside prices (e.g. "14% VAT included"). */
        private String priceNote;
        /** "required" where the law or licence records a government ID at every SIM sale (Guyana); else off. */
        private String simRegistration;
        /** The operator's front door: how customers reach it and where its apps and legal pages live. */
        private String supportPhone;
        private String supportWhatsapp;
        private String supportEmail;
        private String appStoreUrl;
        private String playStoreUrl;
        private String privacyUrl;
        private String termsUrl;
        /** Any URL — our TMF667 endpoint by default, a CMS CDN if the operator brings one. */
        private String logoUrl;
        /**
         * Agentic-commerce exposure: "off" (dark to AI shopping agents),
         * "discovery" (findable — feed only, no agent checkout), or "full".
         * Defaults to off: being shopped by agents is opt-in, never assumed.
         */
        private String agentCommerce = "off";
        /**
         * Whether the consumer storefront shows the B2B lead-capture ("Something
         * bigger in mind? — fleets, offices, IoT"). Off by default: an operator
         * with no business line shouldn't advertise one on its consumer shop.
         */
        private boolean businessSales = false;
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

        public String getTagline() {
            return tagline;
        }

        public void setTagline(String tagline) {
            this.tagline = tagline;
        }

        public String getPriceParityMode() {
            return priceParityMode == null || priceParityMode.isBlank() ? "uniform" : priceParityMode;
        }

        public void setPriceParityMode(String v) {
            this.priceParityMode = v;
        }

        public boolean isSandbox() {
            return sandbox;
        }

        public void setSandbox(boolean v) {
            this.sandbox = v;
        }

        public String getCatalogGovernance() {
            return catalogGovernance == null || catalogGovernance.isBlank() ? "direct" : catalogGovernance;
        }

        public void setCatalogGovernance(String v) {
            this.catalogGovernance = v;
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

        public String getTimezone() {
            return timezone;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public Integer getPriceDecimals() {
            return priceDecimals;
        }

        public void setPriceDecimals(Integer priceDecimals) {
            this.priceDecimals = priceDecimals;
        }

        public String getCurrencyDisplay() {
            return currencyDisplay;
        }

        public void setCurrencyDisplay(String currencyDisplay) {
            this.currencyDisplay = currencyDisplay;
        }

        public String getPriceNote() {
            return priceNote;
        }

        public String getSimRegistration() {
            return simRegistration;
        }

        public String getSupportPhone() {
            return supportPhone;
        }

        public void setSupportPhone(String v) {
            this.supportPhone = v;
        }

        public String getSupportWhatsapp() {
            return supportWhatsapp;
        }

        public void setSupportWhatsapp(String v) {
            this.supportWhatsapp = v;
        }

        public String getSupportEmail() {
            return supportEmail;
        }

        public void setSupportEmail(String v) {
            this.supportEmail = v;
        }

        public String getAppStoreUrl() {
            return appStoreUrl;
        }

        public void setAppStoreUrl(String v) {
            this.appStoreUrl = v;
        }

        public String getPlayStoreUrl() {
            return playStoreUrl;
        }

        public void setPlayStoreUrl(String v) {
            this.playStoreUrl = v;
        }

        public String getPrivacyUrl() {
            return privacyUrl;
        }

        public void setPrivacyUrl(String v) {
            this.privacyUrl = v;
        }

        public String getTermsUrl() {
            return termsUrl;
        }

        public void setTermsUrl(String v) {
            this.termsUrl = v;
        }

        public void setSimRegistration(String simRegistration) {
            this.simRegistration = simRegistration;
        }

        public void setPriceNote(String priceNote) {
            this.priceNote = priceNote;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
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

        public String getAgentCommerce() {
            return agentCommerce;
        }

        public void setAgentCommerce(String agentCommerce) {
            this.agentCommerce = agentCommerce;
        }

        public boolean isBusinessSales() {
            return businessSales;
        }

        public void setBusinessSales(boolean businessSales) {
            this.businessSales = businessSales;
        }

        public List<String> getHosts() {
            return hosts;
        }

        public void setHosts(List<String> hosts) {
            this.hosts = hosts;
        }
    }
}
