package com.bss.billing.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The deployment's tenants: which OIDC issuers are trusted and which tenant
 * each one IS. Tenancy derives from the verified token issuer — never from a
 * user-editable claim — so a tenant can be a Keycloak realm here, a Cognito
 * pool or an Entra tenant in the cloud, without code changes. Anonymous
 * traffic belongs to the default tenant until the gateway maps hostnames.
 */
@Component
@ConfigurationProperties(prefix = "bss.tenants")
public class TenantRegistry {

    private String defaultTenant = "genalpha";
    private List<TenantEntry> registry = new ArrayList<>();

    public TenantEntry byIssuer(String issuer) {
        if (issuer == null) {
            return null;
        }
        return registry.stream().filter(t -> issuer.equals(t.getIssuer())).findFirst().orElse(null);
    }

    public TenantEntry byId(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        return registry.stream().filter(t -> tenantId.equals(t.getId())).findFirst().orElse(null);
    }

    public String defaultTenantId() {
        return defaultTenant;
    }

    public String getDefaultTenant() {
        return defaultTenant;
    }

    public void setDefaultTenant(String defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    public List<TenantEntry> getRegistry() {
        return registry;
    }

    public void setRegistry(List<TenantEntry> registry) {
        this.registry = registry;
    }

    public static class TenantEntry {

        private String id;
        private String issuer;
        /** Backchannel JWKS endpoint; empty means discover from the issuer. */
        private String jwksUri;
        /** Client-credentials endpoint + machine client for THIS tenant's IdP
         * (only services that call other services use these). */
        private String tokenUri;
        private String machineClientId;
        private String machineClientSecret;
        /** Bill distribution seam: where finished bills go — a Peppol
         * access point or a print house. none = the bill stays in-app. */
        private String billDistributionProvider = "none";
        private String billDistributionUrl;
        private String billDistributionToken;
        /** ehf (Norway CIUS of Peppol BIS 3.0) | peppol | cii (EN 16931's
         * other syntax). */
        private String billDistributionFormat = "peppol";
        /** einvoice (XML to the access point) | print (PDF to the print
         * house). */
        private String billDistributionChannel = "einvoice";

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

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getMachineClientId() {
            return machineClientId;
        }

        public void setMachineClientId(String machineClientId) {
            this.machineClientId = machineClientId;
        }

        public String getMachineClientSecret() {
            return machineClientSecret;
        }

        public void setMachineClientSecret(String machineClientSecret) {
            this.machineClientSecret = machineClientSecret;
        }

        public String getBillDistributionProvider() { return billDistributionProvider; }
        public void setBillDistributionProvider(String v) { this.billDistributionProvider = v; }
        public String getBillDistributionUrl() { return billDistributionUrl; }
        public void setBillDistributionUrl(String v) { this.billDistributionUrl = v; }
        public String getBillDistributionToken() { return billDistributionToken; }
        public void setBillDistributionToken(String v) { this.billDistributionToken = v; }
        public String getBillDistributionFormat() { return billDistributionFormat; }
        public void setBillDistributionFormat(String v) { this.billDistributionFormat = v; }
        public String getBillDistributionChannel() { return billDistributionChannel; }
        public void setBillDistributionChannel(String v) { this.billDistributionChannel = v; }
    }
}
