package com.bss.userroles.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPERATOR-AS-A-FORM: everything ops/onboard-tenant.sh does, as a
 * service act — clone the template realm (clients, roles, machine
 * service accounts; personas dropped, object ids stripped so the clone
 * mints its own), append the tenant block to the SHARED registry file
 * (which every service live-refreshes — no restart), and seed a starter
 * catalog in the newborn's own currency. The host operator's admin
 * presses a button; an operator exists.
 */
@Service
public class TenantOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(TenantOnboardingService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SAFE_ID = Pattern.compile("[a-z][a-z0-9]{2,20}");

    private final RestClient rest;
    private final String keycloakBase;
    private final String adminUser;
    private final String adminPassword;
    private final String templatePath;
    private final String tenantsFile;
    private final String catalogBase;
    private final com.bss.userroles.security.TenantRegistry tenants;
    private final String protectedTenants;
    private final IdpAdminClient idp;
    private final com.bss.userroles.security.TenantFileRefresher refresher;

    public TenantOnboardingService(RestClient.Builder builder,
            @Value("${bss.onboarding.keycloak-base:http://localhost:8085}") String keycloakBase,
            @Value("${bss.onboarding.admin-user:admin}") String adminUser,
            @Value("${bss.onboarding.admin-password:admin}") String adminPassword,
            @Value("${bss.onboarding.realm-template:infra/keycloak/nova-realm.json}") String templatePath,
            @Value("${bss.onboarding.tenants-file:infra/tenants/tenants.yml}") String tenantsFile,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8081}") String catalogBase,
            @Value("${bss.onboarding.protected-tenants:genalpha,nova}") String protectedTenants,
            IdpAdminClient idp,
            com.bss.userroles.security.TenantRegistry tenants,
            com.bss.userroles.security.TenantFileRefresher refresher) {
        this.rest = builder.build();
        this.keycloakBase = keycloakBase;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.templatePath = templatePath;
        this.tenantsFile = tenantsFile;
        this.catalogBase = catalogBase;
        this.protectedTenants = protectedTenants;
        this.idp = idp;
        this.tenants = tenants;
        this.refresher = refresher;
    }

    /** Every operator the registry knows — the console's list view. */
    public List<Map<String, Object>> list() {
        return tenants.getRegistry().stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("id", t.getId());
            map.put("name", t.getBrandName() == null ? t.getId() : t.getBrandName());
            map.put("locale", t.getLocale());
            map.put("currency", t.getCurrency());
            map.put("issuer", t.getIssuer());
            map.put("@type", "Operator");
            return map;
        }).toList();
    }

    public Map<String, Object> onboard(Map<String, Object> dto) throws Exception {
        String id = String.valueOf(dto.get("id")).toLowerCase().trim();
        if (!SAFE_ID.matcher(id).matches() || "master".equals(id)) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "operator id: 3-21 chars, a-z0-9, starting with a letter");
        }
        String name = dto.get("name") == null ? id : String.valueOf(dto.get("name"));
        String locale = dto.get("locale") == null ? "en" : String.valueOf(dto.get("locale"));
        String currency = dto.get("currency") == null ? "EUR" : String.valueOf(dto.get("currency"));
        String color = dto.get("color") == null ? "#B85C38" : String.valueOf(dto.get("color"));
        long t0 = System.currentTimeMillis();

        String adminToken = masterAdminToken();
        // idempotent: a re-onboard replaces the realm and the block
        try {
            rest.delete().uri(keycloakBase + "/admin/realms/" + id)
                    .header("Authorization", "Bearer " + adminToken).retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            // no prior realm — the common case
        }
        rest.post().uri(keycloakBase + "/admin/realms")
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .body(realmClone(id, name))
                .retrieve().toBodilessEntity();
        log.info("realm '{}' created from the template", id);

        // the realm was replaced: any cached machine token for it is now a lie
        idp.evictTokens(id);
        appendTenantBlock(id, name, locale, currency, color);
        // this service joins its own fleet immediately; the rest follow
        // within one refresh interval
        refresher.refresh();

        seedCatalog(id, name, currency);
        long seconds = (System.currentTimeMillis() - t0) / 1000;
        log.info("operator '{}' ({}) is LIVE in {}s — no restart, no rebuild", name, id, seconds);
        return Map.of("id", id, "name", name, "locale", locale, "currency", currency,
                "storefrontHost", "shop." + id + ".localhost", "seconds", seconds);
    }

    /**
     * LIVE MUTATION of a serving operator: brand, locale, currency, color
     * are rewritten in the tenant's block and the fleet's refreshers carry
     * them out within one interval — no restart. Identity (id, issuer,
     * key endpoints) is deliberately NOT editable here.
     */
    public Map<String, Object> mutate(String id, Map<String, Object> dto) throws Exception {
        // the SEED operators are env-governed and form-protected — only
        // form-born operators are form-mutable
        if (java.util.Set.of(protectedTenants.split(",")).contains(id)) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "operator '" + id + "' is a seed operator — its config is env, not form");
        }
        if (tenants.byId(id) == null) {
            throw new com.bss.userroles.exception.NotFoundException("Operator '" + id + "' not found");
        }
        String yml = Files.readString(Path.of(tenantsFile));
        Matcher m = Pattern.compile("(      - id: " + id + "\n(?:        .*\n)*)").matcher(yml);
        if (!m.find()) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "operator '" + id + "' is a built-in — built-ins mutate by env, not by form");
        }
        String block = m.group(1);
        if (dto.get("name") != null) {
            block = block.replaceAll("brand-name: .*", "brand-name: " + dto.get("name"));
        }
        if (dto.get("color") != null) {
            block = block.replaceAll("brand-color: .*", "brand-color: \"" + dto.get("color") + "\"");
        }
        if (dto.get("locale") != null) {
            block = block.replaceAll("locale: .*", "locale: \"" + dto.get("locale") + "\"");
        }
        if (dto.get("currency") != null) {
            block = block.replaceAll("currency: .*", "currency: " + dto.get("currency"));
        }
        Files.writeString(Path.of(tenantsFile), yml.replace(m.group(1), block));
        refresher.refresh();
        log.info("operator '{}' mutated LIVE — the fleet follows within one refresh interval", id);
        return Map.of("id", id, "mutated", true);
    }

    private String masterAdminToken() {
        Map<String, Object> res = rest.post()
                .uri(keycloakBase + "/realms/master/protocol/openid-connect/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=password&client_id=admin-cli&username=" + adminUser
                        + "&password=" + adminPassword)
                .retrieve().body(Map.class);
        return String.valueOf(res.get("access_token"));
    }

    /** nova's realm shape with this operator's identity: personas dropped,
     * hosts re-pointed, object ids stripped so the clone mints its own. */
    String realmClone(String id, String name) throws Exception {
        String raw = Files.readString(Path.of(templatePath));
        for (String channel : List.of("shop", "csr", "console", "biz")) {
            raw = raw.replace(channel + ".nova.localhost", channel + "." + id + ".localhost");
        }
        ObjectNode realm = (ObjectNode) JSON.readTree(raw);
        realm.put("realm", id);
        realm.put("displayName", name);
        ArrayNode users = JSON.createArrayNode();
        for (JsonNode u : realm.withArray("users")) {
            String username = u.path("username").asText();
            if ("demo".equals(username) || username.startsWith("service-account-")) {
                users.add(u);
            }
        }
        realm.set("users", users);
        stripIds(realm);
        return JSON.writeValueAsString(realm);
    }

    private void stripIds(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            obj.remove("id");
            obj.remove("containerId");
            Iterator<JsonNode> it = obj.elements();
            while (it.hasNext()) {
                stripIds(it.next());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                stripIds(child);
            }
        }
    }

    /** The same block transform the shell script does — nova's union
     * entry re-suffixed for the newborn, backchannel pinned in-network. */
    void appendTenantBlock(String id, String name, String locale, String currency,
            String color) throws Exception {
        String yml = Files.readString(Path.of(tenantsFile));
        yml = yml.replaceAll("(?m)^      - id: " + id + "\\n(        .*\\n)*", "");
        Matcher m = Pattern.compile("(      - id: nova\\n(?:        .*\\n)*)").matcher(yml);
        if (!m.find()) {
            throw new IllegalStateException("tenants.yml has no nova block to clone");
        }
        String block = m.group(1)
                .replace("- id: nova", "- id: " + id)
                .replace("realms/nova", "realms/" + id)
                .replaceAll("\\$\\{(\\w+)_NOVA:([^}]*)\\}", "\\${$1_" + id.toUpperCase() + ":$2}")
                .replaceAll("jwks-uri: \\$\\{[^}]*\\}", "jwks-uri: http://keycloak:8080/realms/"
                        + id + "/protocol/openid-connect/certs")
                .replaceAll("token-uri: \\$\\{[^}]*\\}", "token-uri: http://keycloak:8080/realms/"
                        + id + "/protocol/openid-connect/token")
                .replaceAll("brand-name: .*", "brand-name: " + name)
                .replaceAll("brand-color: .*", "brand-color: \"" + color + "\"")
                .replaceAll("locale: .*", "locale: \"" + locale + "\"")
                .replaceAll("currency: .*", "currency: " + currency)
                .replaceAll("hosts: .*", "hosts: [shop." + id + ".localhost, csr." + id
                        + ".localhost, console." + id + ".localhost, biz." + id + ".localhost]");
        Files.writeString(Path.of(tenantsFile), yml + block);
        log.info("tenant block '{}' appended to {}", id, tenantsFile);
    }

    /** A starter catalog in the newborn's own currency, seeded with the
     * cloned realm's own staff credential. */
    private void seedCatalog(String id, String name, String currency) {
        Map<String, Object> tokenRes = rest.post()
                .uri(keycloakBase + "/realms/" + id + "/protocol/openid-connect/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=password&client_id=bss-demo&username=demo&password=demo")
                .retrieve().body(Map.class);
        String staff = String.valueOf(tokenRes.get("access_token"));
        // the RUNNING fleet adopts the newborn within one refresh interval —
        // wait for the catalog to honor her first token before seeding
        for (int i = 0; i < 30; i++) {
            try {
                rest.get().uri(catalogBase + "/tmf-api/productCatalogManagement/v4/productOffering?limit=1")
                        .header("Authorization", "Bearer " + staff).retrieve().toBodilessEntity();
                break;
            } catch (Exception notYet) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        Map<String, Object> price = rest.post()
                .uri(catalogBase + "/tmf-api/productCatalogManagement/v4/productOfferingPrice")
                .header("Authorization", "Bearer " + staff)
                .header("Content-Type", "application/json")
                .body(Map.of("name", name + " Mobile M monthly", "priceType", "recurring",
                        "recurringChargePeriodType", "month", "recurringChargePeriodLength", 1,
                        "lifecycleStatus", "Active",
                        "price", Map.of("unit", currency, "value", 249.0)))
                .retrieve().body(Map.class);
        rest.post().uri(catalogBase + "/tmf-api/productCatalogManagement/v4/productOffering")
                .header("Authorization", "Bearer " + staff)
                .header("Content-Type", "application/json")
                .body(Map.of("name", name + " Mobile M", "lifecycleStatus", "Active",
                        "isBundle", false,
                        "productOfferingPrice", List.of(Map.of("id", price.get("id"),
                                "name", name + " Mobile M monthly"))))
                .retrieve().toBodilessEntity();
        log.info("starter catalog seeded for '{}' in {}", id, currency);
    }
}
