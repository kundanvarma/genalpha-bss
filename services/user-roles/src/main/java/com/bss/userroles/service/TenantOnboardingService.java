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
    private final String policyBase;
    private final String partyBase;
    private final String usageBase;
    private final String billingBase;
    private final String somBase;
    private final String inventoryBase;
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
            @Value("${bss.downstream.policy-base-url:http://localhost:8113}") String policyBase,
            @Value("${bss.downstream.party-base-url:http://localhost:8083}") String partyBase,
            @Value("${bss.downstream.usage-base-url:http://localhost:8097}") String usageBase,
            @Value("${bss.downstream.billing-base-url:http://localhost:8086}") String billingBase,
            @Value("${bss.downstream.som-base-url:http://localhost:8104}") String somBase,
            @Value("${bss.downstream.inventory-base-url:http://localhost:8084}") String inventoryBase,
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
        this.policyBase = policyBase;
        this.partyBase = partyBase;
        this.usageBase = usageBase;
        this.billingBase = billingBase;
        this.somBase = somBase;
        this.inventoryBase = inventoryBase;
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
            map.put("agentCommerce", t.getAgentCommerce());
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
        // build the clone BEFORE deleting the old realm — a bad/stale
        // template must never destroy a serving realm
        String realmJson = realmClone(id, name);
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
                .body(realmJson)
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
    /** The caller's OWN brand card — the fields a hosted operator's
     *  marketing team may read: name, color, tagline. Nothing operational. */
    public Map<String, Object> brandOf(String id) throws Exception {
        String yml = Files.readString(Path.of(tenantsFile));
        Matcher m = Pattern.compile("(      - id: " + id + "\n(?:        .*\n)*)").matcher(yml);
        if (!m.find()) {
            throw new com.bss.userroles.exception.NotFoundException("Operator '" + id + "' not found");
        }
        String block = m.group(1);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", id);
        out.put("name", firstGroup(block, "brand-name: (.*)"));
        out.put("color", strip(firstGroup(block, "brand-color: (.*)")));
        out.put("tagline", strip(firstGroup(block, "tagline: (.*)")));
        return out;
    }

    /** Brand-only mutation for the tenant's own team: name, color, tagline —
     *  locale, currency and agent-commerce stay with the host operator. */
    public Map<String, Object> mutateBrand(String id, Map<String, Object> dto) throws Exception {
        Map<String, Object> safe = new java.util.LinkedHashMap<>();
        for (String k : java.util.List.of("name", "color", "tagline")) {
            if (dto.get(k) != null) {
                safe.put(k, dto.get(k));
            }
        }
        if (safe.isEmpty()) {
            return Map.of("id", id, "mutated", false);
        }
        return mutate(id, safe);
    }

    private static String firstGroup(String block, String regex) {
        Matcher m = Pattern.compile(regex).matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String strip(String v) {
        return v != null && v.length() > 1 && v.startsWith("\"") && v.endsWith("\"")
                ? v.substring(1, v.length() - 1) : v;
    }

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
        if (dto.get("catalogGovernance") != null) {
            String mode = String.valueOf(dto.get("catalogGovernance")).trim();
            if (!java.util.Set.of("direct", "governed").contains(mode)) {
                throw new com.bss.userroles.exception.BadRequestException(
                        "catalogGovernance must be direct or governed");
            }
            String line = "catalog-governance: \"" + mode + "\"";
            if (block.contains("catalog-governance: ")) {
                block = block.replaceAll("catalog-governance: .*",
                        java.util.regex.Matcher.quoteReplacement(line));
            } else {
                block = block.replaceFirst("( +)brand-name: ",
                        "$1" + java.util.regex.Matcher.quoteReplacement(line) + "\n$1brand-name: ");
            }
        }
                if (dto.get("priceParityMode") != null) {
            String mode = String.valueOf(dto.get("priceParityMode")).trim();
            if (!java.util.Set.of("uniform", "per-channel").contains(mode)) {
                throw new com.bss.userroles.exception.BadRequestException(
                        "priceParityMode must be uniform or per-channel");
            }
            String line = "price-parity-mode: \"" + mode + "\"";
            if (block.contains("price-parity-mode: ")) {
                block = block.replaceAll("price-parity-mode: .*",
                        java.util.regex.Matcher.quoteReplacement(line));
            } else {
                block = block.replaceFirst("( +)brand-name: ",
                        "$1" + java.util.regex.Matcher.quoteReplacement(line) + "\n$1brand-name: ");
            }
        }
                if (dto.get("tagline") != null) {
            // the storefront hero line — free text, so it rides YML double-quoted;
            // insert-if-absent because older tenant blocks predate the field
            String tagline = String.valueOf(dto.get("tagline")).replace("\"", "'").trim();
            if (tagline.length() > 200) {
                throw new com.bss.userroles.exception.BadRequestException(
                        "tagline must be 200 characters or fewer");
            }
            String line = "tagline: \"" + tagline + "\"";
            if (block.contains("tagline: ")) {
                block = block.replaceAll("tagline: .*", java.util.regex.Matcher.quoteReplacement(line));
            } else {
                block = block.replaceFirst("( +)brand-name: ",
                        "$1" + java.util.regex.Matcher.quoteReplacement(line) + "\n$1brand-name: ");
            }
        }
        if (dto.get("agentCommerce") != null) {
            // The agentic-commerce switch: how much of this operator AI
            // shopping agents may see. Flipping it here live-refreshes the
            // gateway's gate — reversible in one refresh interval.
            String mode = String.valueOf(dto.get("agentCommerce"));
            if (!java.util.Set.of("off", "discovery", "full").contains(mode)) {
                throw new com.bss.userroles.exception.BadRequestException(
                        "agentCommerce must be off, discovery or full");
            }
            block = block.replaceAll("agent-commerce: .*", "agent-commerce: \"" + mode + "\"");
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
                        + ".localhost, console." + id + ".localhost, biz." + id + ".localhost]")
                // a newborn operator is DARK to AI shopping agents until it
                // opts in — being shopped by agents is a choice, not a default
                .replaceAll("agent-commerce: .*", "agent-commerce: \"off\"")
                // newborns are conservative to crawlers too
                .replaceAll("ai-visibility: .*", "ai-visibility: \"search-only\"");
        Files.writeString(Path.of(tenantsFile), yml + block);
        log.info("tenant block '{}' appended to {}", id, tenantsFile);
    }

    /** A starter catalog in the newborn's own currency, seeded with the
     * cloned realm's own staff credential. */
    /**
     * THE SHADOW-OPERATOR CLONE: mint a SANDBOX tenant that is this operator,
     * again — same onboarding machinery, then the source's catalog and rules
     * copied over the tenants' own staff tokens, ids remapped. The sandbox
     * flag rides the tenant block, so the whole fleet knows: real engines,
     * no real-world side effects. Simulation by running the actual thing.
     */
    public Map<String, Object> cloneOperator(String sourceId, Map<String, Object> dto) throws Exception {
        String yml = Files.readString(Path.of(tenantsFile));
        Matcher src = Pattern.compile("(      - id: " + sourceId + "\n(?:        .*\n)*)").matcher(yml);
        if (!src.find()) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "unknown source operator '" + sourceId + "'");
        }
        String block = src.group(1);
        String srcRealm = firstGroup(block, "issuer: .*?/realms/([a-z0-9-]+)");
        if (srcRealm == null) {
            srcRealm = sourceId;
        }
        String id = String.valueOf(dto.get("id")).toLowerCase().trim();
        String name = dto.get("name") == null
                ? strip(firstGroup(block, "brand-name: (.*)")) + " Sandbox" : String.valueOf(dto.get("name"));
        Map<String, Object> seed = new java.util.LinkedHashMap<>();
        seed.put("id", id);
        seed.put("name", name);
        seed.put("locale", strip(orDefault(firstGroup(block, "locale: (.*)"), "en")));
        seed.put("currency", strip(orDefault(firstGroup(block, "currency: (.*)"), "EUR")));
        seed.put("color", strip(orDefault(firstGroup(block, "brand-color: (.*)"), "#B85C38")));
        Map<String, Object> made = onboard(seed);
        markSandbox(id);
        refresher.refresh();
        String srcTok = staffToken(srcRealm);
        String dstTok = staffToken(id);
        waitAdopt(catalogBase, "/tmf-api/productCatalogManagement/v4/productOffering", dstTok);
        waitAdopt(policyBase, "/tmf-api/policyManagement/v4/policyRule", dstTok);
        waitAdopt(usageBase, "/tmf-api/usageManagement/v4/wholesaleRateCard", dstTok);
        Map<String, Object> copied = copyCatalog(srcTok, dstTok);
        copied.put("policyRules", copyPolicyRules(srcTok, dstTok));
        copied.put("rateCards", copyRateCards(srcTok, dstTok));
        log.info("sandbox clone '{}' of '{}' is LIVE — copied {}", id, sourceId, copied);
        Map<String, Object> out = new java.util.LinkedHashMap<>(made);
        out.put("sourceId", sourceId);
        out.put("sandbox", true);
        out.put("copied", copied);
        return out;
    }

    /**
     * TVILLING BASE SEEDING: give the sandbox clone a subscriber base that is
     * STRUCTURALLY the source's and PERSONALLY nobody's. The only thing read
     * from the source is the AGGREGATE shape — how many active products sit
     * on which offering, by name; no customer name, email or id ever crosses.
     * The clone then mints that many synthetic twins (fictional by
     * construction) and gives each its product, so the real billing engine
     * has a real base to bill. Refused outside a sandbox: twins in
     * production would be pollution, not simulation.
     */
    public Map<String, Object> seedTwinBase(String cloneId, Map<String, Object> dto) throws Exception {
        String yml = Files.readString(Path.of(tenantsFile));
        Matcher cm = Pattern.compile("(      - id: " + cloneId + "\n(?:        .*\n)*)").matcher(yml);
        if (!cm.find() || !cm.group(1).contains("sandbox:")) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "twin seeding is sandbox-only — '" + cloneId + "' is not a sandbox clone");
        }
        String sourceId = String.valueOf(dto.getOrDefault("sourceId", "genalpha"));
        Matcher sm = Pattern.compile("(      - id: " + sourceId + "\n(?:        .*\n)*)").matcher(yml);
        if (!sm.find()) {
            throw new com.bss.userroles.exception.BadRequestException("unknown source '" + sourceId + "'");
        }
        String srcRealm = orDefault(firstGroup(sm.group(1), "issuer: .*?/realms/([a-z0-9-]+)"), sourceId);
        int cap = dto.get("count") == null ? 25 : Integer.parseInt(String.valueOf(dto.get("count")));
        String srcTok = staffToken(srcRealm);
        String dstTok = staffToken(cloneId);

        // 1. the SHAPE — aggregate only, the sole read from the source
        Map<String, Integer> shape = new java.util.LinkedHashMap<>();
        for (Map<String, Object> product : fetchAll(inventoryBase,
                "/tmf-api/productInventory/v4/product?status=active", srcTok)) {
            Object ref = product.get("productOffering");
            if (ref instanceof Map<?, ?> r && r.get("name") != null) {
                shape.merge(String.valueOf(r.get("name")), 1, Integer::sum);
            }
        }
        int total = shape.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            throw new com.bss.userroles.exception.BadRequestException("the source has no active base to shape from");
        }

        // 2. the clone's own shelf, by name
        Map<String, Map<String, Object>> shelf = new java.util.HashMap<>();
        for (Map<String, Object> o : fetchAll(catalogBase,
                "/tmf-api/productCatalogManagement/v4/productOffering", dstTok)) {
            shelf.putIfAbsent(String.valueOf(o.get("name")), o);
        }

        // 3. mint the twins — deterministic, proportional, fictional
        Map<String, Integer> seededBy = new java.util.LinkedHashMap<>();
        int seeded = 0;
        long stamp = System.currentTimeMillis();
        for (Map.Entry<String, Integer> e : shape.entrySet()) {
            Map<String, Object> offering = shelf.get(e.getKey());
            if (offering == null) {
                continue;
            }
            int n = Math.max(1, Math.round((float) cap * e.getValue() / total));
            for (int i = 0; i < n; i++) {
                String label = "Twin-" + Integer.toHexString((e.getKey() + i).hashCode());
                Map<String, Object> party;
                try {
                    party = rest.post().uri(partyBase + "/tmf-api/party/v4/individual")
                            .header("Authorization", "Bearer " + dstTok)
                            .header("Content-Type", "application/json")
                            .body(Map.of("givenName", "Tvilling", "familyName", label,
                                    "contactMedium", java.util.List.of(Map.of(
                                            "mediumType", "email", "characteristic",
                                            Map.of("emailAddress", label.toLowerCase() + "-" + stamp + "@twin.example")))))
                            .retrieve().body(Map.class);
                } catch (Exception ex) {
                    log.warn("twin party skipped: {}", ex.getMessage());
                    continue;
                }
                try {
                    rest.post().uri(inventoryBase + "/tmf-api/productInventory/v4/product")
                            .header("Authorization", "Bearer " + dstTok)
                            .header("Content-Type", "application/json")
                            .body(Map.of("name", e.getKey(), "status", "active",
                                    "startDate", java.time.OffsetDateTime.now().toString(),
                                    "productOffering", Map.of("id", offering.get("id"), "name", e.getKey()),
                                    "relatedParty", java.util.List.of(Map.of(
                                            "id", party.get("id"), "role", "customer",
                                            "@referredType", "Individual"))))
                            .retrieve().body(Map.class);
                    seeded++;
                    seededBy.merge(e.getKey(), 1, Integer::sum);
                } catch (Exception ex) {
                    log.warn("twin product skipped for {}: {}", e.getKey(), ex.getMessage());
                }
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("cloneId", cloneId);
        out.put("sourceId", sourceId);
        out.put("seeded", seeded);
        out.put("distribution", seededBy);
        out.put("privacy", "only the AGGREGATE offering distribution was read from the source — "
                + "no name, email or id crossed; every subscriber here is fictional by construction");
        log.info("twin base seeded into sandbox '{}': {} subscribers shaped like '{}'",
                cloneId, seeded, sourceId);
        return out;
    }

    /** T1 — advance a SANDBOX clone's clock (clock-offset-days in its block). */
    public Map<String, Object> advanceClock(String cloneId, int days) throws Exception {
        String yml = Files.readString(Path.of(tenantsFile));
        Matcher m = Pattern.compile("(      - id: " + cloneId + "\n(?:        .*\n)*)").matcher(yml);
        if (!m.find() || !m.group(1).contains("sandbox:")) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "the clock only moves in a sandbox — '" + cloneId + "' is not one");
        }
        String block = m.group(1);
        int current = 0;
        String cur = firstGroup(block, "clock-offset-days: \"?(\\d+)");
        if (cur != null) {
            current = Integer.parseInt(cur);
        }
        int next = current + days;
        String updated = block.contains("clock-offset-days:")
                ? block.replaceAll("clock-offset-days: \"?\\d+\"?", "clock-offset-days: \"" + next + "\"")
                : block.replaceFirst("( +)sandbox: ", "$1clock-offset-days: \"" + next + "\"\n$1sandbox: ");
        Files.writeString(Path.of(tenantsFile), yml.replace(block, updated));
        refresher.refresh();
        return Map.of("cloneId", cloneId, "clockOffsetDays", next);
    }

    /** T1 — the simulated quarter: 3 x (advance 30 days -> REAL billing run).
     *  Recurring charges only until T3; the report says so on its face. */
    public Map<String, Object> simulateQuarter(String cloneId) throws Exception {
        String dstTok = staffToken(cloneId);
        java.util.List<Map<String, Object>> cycles = new java.util.ArrayList<>();
        for (int cycle = 1; cycle <= 3; cycle++) {
            int offset = (Integer) advanceClock(cloneId, 30).get("clockOffsetDays");
            try {
                // the fleet learns the new clock on its refresh tick — outwait it
                Thread.sleep(35000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            Map<String, Object> run;
            try {
                run = rest.post().uri(billingBase + "/tmf-api/customerBillManagement/v4/billingRun")
                        .header("Authorization", "Bearer " + dstTok)
                        .header("Content-Type", "application/json")
                        .body(Map.of()).retrieve().body(Map.class);
            } catch (Exception e) {
                run = Map.of("error", String.valueOf(e.getMessage()));
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("cycle", cycle);
            row.put("clockOffsetDays", offset);
            row.put("run", run);
            cycles.add(row);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("@type", "SimulatedQuarter");
        out.put("cloneId", cloneId);
        out.put("cycle", cycles);
        out.put("assumptions", java.util.List.of(
                "dates were compressed: 3 cycles of +30 days on the sandbox clock",
                "recurring charges only — usage-dependent lines reflect seeded meters, not a lived quarter",
                "billed by the SAME engine as production; no day billed twice across cycles"));
        return out;
    }

    /**
     * PS — THE PRE-SALES PROSPECT SIMULATOR: "your business on our BSS", in
     * the first meeting. Input is PUBLIC: the prospect's price list and an
     * assumed base mix — no data of theirs is touched. A fresh SANDBOX
     * operator is minted, its shelf built from the price list, a twin base
     * minted at the assumed mix, and the REAL engines run a compressed
     * quarter. The report carries its assumptions on its face; the sandbox
     * survives for the live walk-through and dies with its realm.
     */
    public Map<String, Object> prospectSimulation(Map<String, Object> dto) throws Exception {
        if (!(dto.get("priceList") instanceof java.util.List<?> priceList) || priceList.isEmpty()) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "priceList [{offeringName, monthly}] is required — the prospect's PUBLIC tariffs");
        }
        String id = dto.get("id") == null
                ? "ps" + String.valueOf(System.currentTimeMillis()).substring(7)
                : String.valueOf(dto.get("id"));
        String name = String.valueOf(dto.getOrDefault("name", "Prospect"));
        String currency = String.valueOf(dto.getOrDefault("currency", "EUR"));
        Map<String, Object> seed = new java.util.LinkedHashMap<>();
        seed.put("id", id);
        seed.put("name", name + " (simulation)");
        seed.put("currency", currency);
        seed.put("locale", dto.getOrDefault("locale", "en"));
        onboard(seed);
        markSandbox(id);
        refresher.refresh();
        String tok = staffToken(id);
        waitAdopt(catalogBase, "/tmf-api/productCatalogManagement/v4/productOffering", tok);

        // the shelf, from the PUBLIC price list — categorized, because the
        // storefront's line-of-business tabs hide category-less offerings
        String categoryName = String.valueOf(dto.getOrDefault("category", "Broadband"));
        String categoryId = null;
        try {
            Map<String, Object> cat = rest.post()
                    .uri(catalogBase + "/tmf-api/productCatalogManagement/v4/category")
                    .header("Authorization", "Bearer " + tok)
                    .header("Content-Type", "application/json")
                    .body(Map.of("name", categoryName, "lifecycleStatus", "Active"))
                    .retrieve().body(Map.class);
            categoryId = String.valueOf(cat.get("id"));
        } catch (Exception e) {
            log.warn("prospect category skipped: {}", e.getMessage());
        }
        java.math.BigDecimal bookMonthly = java.math.BigDecimal.ZERO;
        Map<String, String> offeringByName = new java.util.LinkedHashMap<>();
        for (Object raw : priceList) {
            Map<?, ?> row = (Map<?, ?>) raw;
            String offName = String.valueOf(row.get("offeringName"));
            java.math.BigDecimal monthly = new java.math.BigDecimal(String.valueOf(row.get("monthly")));
            try {
                Map<String, Object> price = rest.post()
                        .uri(catalogBase + "/tmf-api/productCatalogManagement/v4/productOfferingPrice")
                        .header("Authorization", "Bearer " + tok)
                        .header("Content-Type", "application/json")
                        .body(Map.of("name", offName + " monthly", "priceType", "recurring",
                                "recurringChargePeriodType", "month", "recurringChargePeriodLength", 1,
                                "lifecycleStatus", "Active",
                                "price", Map.of("unit", currency, "value", monthly)))
                        .retrieve().body(Map.class);
                Map<String, Object> offBody = new java.util.LinkedHashMap<>();
                offBody.put("name", offName);
                offBody.put("lifecycleStatus", "Active");
                offBody.put("isSellable", true);
                offBody.put("productOfferingPrice", java.util.List.of(
                        Map.of("id", price.get("id"), "name", price.get("name"))));
                if (categoryId != null) {
                    offBody.put("category", java.util.List.of(
                            Map.of("id", categoryId, "name", categoryName)));
                }
                Map<String, Object> off = rest.post()
                        .uri(catalogBase + "/tmf-api/productCatalogManagement/v4/productOffering")
                        .header("Authorization", "Bearer " + tok)
                        .header("Content-Type", "application/json")
                        .body(offBody)
                        .retrieve().body(Map.class);
                offeringByName.put(offName, String.valueOf(off.get("id")));
            } catch (Exception e) {
                log.warn("prospect shelf row skipped '{}': {}", offName, e.getMessage());
            }
        }

        // the assumed base — twins at the prospect's mix
        int seeded = 0;
        long stamp = System.currentTimeMillis();
        if (dto.get("baseMix") instanceof java.util.List<?> mix) {
            waitAdopt(partyBase, "/tmf-api/party/v4/individual", tok);
            waitAdopt(inventoryBase, "/tmf-api/productInventory/v4/product", tok);
            for (Object raw : mix) {
                Map<?, ?> row = (Map<?, ?>) raw;
                String offName = String.valueOf(row.get("offeringName"));
                String offId = offeringByName.get(offName);
                if (offId == null) {
                    continue;
                }
                int n = Integer.parseInt(String.valueOf(row.get("subscribers")));
                for (int i = 0; i < n; i++) {
                    seeded += mintTwin(tok, offName, offId, stamp, i) ? 1 : 0;
                }
            }
        }

        // the quarter, on the real engines
        Map<String, Object> quarter = seeded > 0 ? simulateQuarter(id) : Map.of("skipped", "no base mix given");

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("@type", "ProspectSimulation");
        out.put("sandboxId", id);
        out.put("prospect", name);
        out.put("shelf", offeringByName.size());
        out.put("twinBase", seeded);
        out.put("quarter", quarter);
        out.put("assumptions", java.util.List.of(
                "input was PUBLIC only: the prospect's price list and an assumed mix — none of their data was touched",
                "billed by the SAME engines that cut production bills, over a compressed quarter",
                "the sandbox is walled from the outside world and dies with its realm"));
        return out;
    }

    /** One synthetic subscriber with one product — shared by twin seeding. */
    private boolean mintTwin(String tok, String offName, String offId, long stamp, int i) {
        String label = "Twin-" + Integer.toHexString((offName + i).hashCode());
        try {
            Map<String, Object> party = rest.post().uri(partyBase + "/tmf-api/party/v4/individual")
                    .header("Authorization", "Bearer " + tok)
                    .header("Content-Type", "application/json")
                    .body(Map.of("givenName", "Tvilling", "familyName", label,
                            "contactMedium", java.util.List.of(Map.of(
                                    "mediumType", "email", "characteristic",
                                    Map.of("emailAddress", label.toLowerCase() + "-" + stamp + "@twin.example")))))
                    .retrieve().body(Map.class);
            rest.post().uri(inventoryBase + "/tmf-api/productInventory/v4/product")
                    .header("Authorization", "Bearer " + tok)
                    .header("Content-Type", "application/json")
                    .body(Map.of("name", offName, "status", "active",
                            "startDate", java.time.OffsetDateTime.now().toString(),
                            "productOffering", Map.of("id", offId, "name", offName),
                            "relatedParty", java.util.List.of(Map.of(
                                    "id", party.get("id"), "role", "customer",
                                    "@referredType", "Individual"))))
                    .retrieve().body(Map.class);
            return true;
        } catch (Exception e) {
            log.warn("twin mint skipped: {}", e.getMessage());
            return false;
        }
    }

    /**
     * B-M1 — THE REAL BASE IMPORTER: the migration door. Each legacy row
     * becomes a LOGIN (temporary password the operator hands over), a party,
     * and an active product carrying the customer's real MSISDN — created
     * with the TARGET tenant's own staff token, idempotent per email
     * (a re-run counts alreadyPresent and touches nothing). The report is
     * S6-style: what landed, what already existed, which offerings are
     * missing BY NAME, and what this version does not do — on its face.
     */
    public Map<String, Object> importBase(String tenantId, Map<String, Object> dto) throws Exception {
        if (!(dto.get("rows") instanceof java.util.List<?> rows) || rows.isEmpty()) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "rows [{externalRef, givenName, familyName, email, msisdn?, offeringName}] are required");
        }
        String tok = staffToken(tenantId);
        String self = "http://localhost:8080";
        waitAdopt(catalogBase, "/tmf-api/productCatalogManagement/v4/productOffering", tok);
        waitAdopt(inventoryBase, "/tmf-api/productInventory/v4/product", tok);

        Map<String, Map<String, Object>> shelf = new java.util.HashMap<>();
        for (Map<String, Object> o : fetchAll(catalogBase,
                "/tmf-api/productCatalogManagement/v4/productOffering", tok)) {
            shelf.putIfAbsent(String.valueOf(o.get("name")), o);
        }

        java.util.List<Map<String, Object>> imported = new java.util.ArrayList<>();
        java.util.List<String> alreadyPresent = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> offeringMissing = new java.util.ArrayList<>();
        java.util.List<Map<String, Object>> failed = new java.util.ArrayList<>();
        for (Object raw : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) raw;
            String externalRef = String.valueOf(row.get("externalRef"));
            String email = String.valueOf(row.get("email"));
            String offeringName = String.valueOf(row.get("offeringName"));
            Map<String, Object> offering = shelf.get(offeringName);
            if (offering == null) {
                Map<String, Object> miss = new java.util.LinkedHashMap<>();
                miss.put("externalRef", externalRef);
                miss.put("offeringName", offeringName);
                miss.put("reason", "no offering with this name in the target catalog — author it first");
                offeringMissing.add(miss);
                continue;
            }
            Map<String, Object> login;
            try {
                login = rest.post().uri(self + "/tmf-api/rolesAndPermissionsManagement/v4/user")
                        .header("Authorization", "Bearer " + tok)
                        .header("Content-Type", "application/json")
                        .body(Map.of("email", email,
                                "givenName", String.valueOf(row.getOrDefault("givenName", "Imported")),
                                "familyName", String.valueOf(row.getOrDefault("familyName", externalRef))))
                        .retrieve().body(Map.class);
            } catch (Exception dup) {
                // idempotency: this email already has a login — the row is done
                alreadyPresent.add(externalRef);
                continue;
            }
            try {
                Map<String, Object> product = new java.util.LinkedHashMap<>();
                product.put("name", offeringName);
                product.put("status", "active");
                product.put("startDate", java.time.OffsetDateTime.now().toString());
                product.put("productOffering", Map.of("id", offering.get("id"), "name", offeringName));
                product.put("relatedParty", java.util.List.of(Map.of(
                        "id", login.get("id"), "role", "customer", "@referredType", "Individual")));
                if (row.get("msisdn") != null) {
                    product.put("supportingResource", java.util.List.of(
                            Map.of("value", String.valueOf(row.get("msisdn")))));
                }
                rest.post().uri(inventoryBase + "/tmf-api/productInventory/v4/product")
                        .header("Authorization", "Bearer " + tok)
                        .header("Content-Type", "application/json")
                        .body(product).retrieve().body(Map.class);
                // the number the portal shows lives on the SERVICE — register
                // the existing MSISDN as data, no pool draw, no order
                rest.post().uri(somBase + "/som/v1/importService")
                        .header("Authorization", "Bearer " + tok)
                        .header("Content-Type", "application/json")
                        .body(Map.of("ownerPartyId", login.get("id"), "name", offeringName,
                                "msisdn", row.get("msisdn") == null ? "" : String.valueOf(row.get("msisdn"))))
                        .retrieve().body(Map.class);
                Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("externalRef", externalRef);
                out.put("partyId", login.get("id"));
                out.put("email", email);
                out.put("temporaryPassword", login.get("temporaryPassword"));
                out.put("offeringName", offeringName);
                if (row.get("msisdn") != null) {
                    out.put("msisdn", row.get("msisdn"));
                }
                imported.add(out);
            } catch (Exception e) {
                Map<String, Object> f = new java.util.LinkedHashMap<>();
                f.put("externalRef", externalRef);
                f.put("reason", String.valueOf(e.getMessage()));
                failed.add(f);
            }
        }
        Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("@type", "BaseImport");
        report.put("tenantId", tenantId);
        report.put("rows", rows.size());
        report.put("imported", imported.size());
        report.put("alreadyPresent", alreadyPresent.size());
        report.put("offeringMissing", offeringMissing.size());
        report.put("failed", failed.size());
        report.put("customers", imported);
        report.put("exceptions", Map.of("offeringMissing", offeringMissing,
                "alreadyPresent", alreadyPresent, "failed", failed));
        report.put("readyForCutover", offeringMissing.isEmpty() && failed.isEmpty());
        report.put("assumptions", java.util.List.of(
                "idempotent per EMAIL: a re-run counts alreadyPresent and creates nothing",
                "each customer gets a LOGIN with a temporary password — hand it over on cutover",
                "the MSISDN rides the product as its supporting resource (the number the portal shows)",
                "NOT in this version: open balances, SIM ICCIDs, port-in orchestration — book balances "
                        + "separately and port numbers in waves"));
        log.info("base import into '{}': {} imported, {} already present, {} missing offerings, {} failed",
                tenantId, imported.size(), alreadyPresent.size(), offeringMissing.size(), failed.size());
        return report;
    }

    private static String orDefault(String v, String dflt) {
        return v == null || v.isBlank() ? dflt : v;
    }

    /** Stamp the clone's block: the whole fleet reads this flag. */
    private void markSandbox(String id) throws Exception {
        String yml = Files.readString(Path.of(tenantsFile));
        Matcher m = Pattern.compile("(      - id: " + id + "\n(?:        .*\n)*)").matcher(yml);
        if (!m.find()) {
            return;
        }
        String block = m.group(1);
        if (!block.contains("sandbox:")) {
            String updated = block.replaceFirst("( +)brand-name: ",
                    "$1sandbox: \"true\"\n$1brand-name: ");
            Files.writeString(Path.of(tenantsFile), yml.replace(block, updated));
        }
    }

    private String staffToken(String realm) {
        Map<String, Object> tokenRes = rest.post()
                .uri(keycloakBase + "/realms/" + realm + "/protocol/openid-connect/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=password&client_id=bss-demo&username=demo&password=demo")
                .retrieve().body(Map.class);
        return String.valueOf(tokenRes.get("access_token"));
    }

    private java.util.List<Map<String, Object>> fetchAll(String base, String path, String token) {
        java.util.List<Map<String, Object>> all = new java.util.ArrayList<>();
        for (int offset = 0; offset < 5000; offset += 100) {
            try {
                java.util.List<Map<String, Object>> page = rest.get()
                        .uri(base + path + (path.contains("?") ? "&" : "?")
                                + "limit=100&offset=" + offset)
                        .header("Authorization", "Bearer " + token)
                        .retrieve().body(java.util.List.class);
                if (page == null || page.isEmpty()) {
                    break;
                }
                all.addAll(page);
                if (page.size() < 100) {
                    break;
                }
            } catch (Exception e) {
                log.warn("clone fetch failed {} at offset {}: {}", path, offset, e.getMessage());
                break;
            }
        }
        return all;
    }

    /** The running fleet adopts a newborn within one refresh interval —
     *  wait until this service honors the clone's token before copying. */
    private void waitAdopt(String base, String probePath, String token) {
        for (int i = 0; i < 30; i++) {
            try {
                rest.get().uri(base + probePath + "?limit=1")
                        .header("Authorization", "Bearer " + token)
                        .retrieve().toBodilessEntity();
                return;
            } catch (Exception notYet) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Any {id: <known old id>} anywhere in the payload follows the map;
     *  href/lastUpdate are dropped — the clone mints its own. */
    @SuppressWarnings("unchecked")
    private Object remapIds(Object node, Map<String, String> ids) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if ("href".equals(k) || "lastUpdate".equals(k)) {
                    continue;
                }
                Object v = e.getValue();
                if ("id".equals(k) && v != null && ids.containsKey(String.valueOf(v))) {
                    out.put(k, ids.get(String.valueOf(v)));
                } else {
                    out.put(k, remapIds(v, ids));
                }
            }
            return out;
        }
        if (node instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object v : l) {
                out.add(remapIds(v, ids));
            }
            return out;
        }
        return node;
    }

    private Map<String, Object> createRemapped(String base, String path, String token,
            Map<String, Object> src, Map<String, String> ids) {
        Map<String, Object> body = new java.util.LinkedHashMap<>((Map<String, Object>) remapIds(src, ids));
        String oldId = String.valueOf(src.get("id"));
        body.remove("id");
        try {
            Map<String, Object> created = rest.post().uri(base + path)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(body).retrieve().body(Map.class);
            if (created != null && created.get("id") != null) {
                ids.put(oldId, String.valueOf(created.get("id")));
                return created;
            }
        } catch (Exception e) {
            log.warn("clone copy skipped {} '{}': {}", path, src.get("name"), e.getMessage());
        }
        return null;
    }

    private Map<String, Object> copyCatalog(String srcTok, String dstTok) {
        String cat = "/tmf-api/productCatalogManagement/v4";
        Map<String, String> ids = new java.util.HashMap<>();
        Map<String, Object> counts = new java.util.LinkedHashMap<>();
        int n = 0;
        for (Map<String, Object> row : fetchAll(catalogBase, cat + "/category", srcTok)) {
            n += createRemapped(catalogBase, cat + "/category", dstTok, row, ids) != null ? 1 : 0;
        }
        counts.put("categories", n);
        n = 0;
        for (Map<String, Object> row : fetchAll(catalogBase, cat + "/productSpecification", srcTok)) {
            n += createRemapped(catalogBase, cat + "/productSpecification", dstTok, row, ids) != null ? 1 : 0;
        }
        counts.put("specifications", n);
        n = 0;
        for (Map<String, Object> row : fetchAll(catalogBase, cat + "/productOfferingPrice", srcTok)) {
            n += createRemapped(catalogBase, cat + "/productOfferingPrice", dstTok, row, ids) != null ? 1 : 0;
        }
        counts.put("prices", n);
        // leaves before bundles: a bundle's children must already exist to remap
        java.util.List<Map<String, Object>> offerings = fetchAll(catalogBase, cat + "/productOffering", srcTok);
        n = 0;
        for (boolean bundlePass : new boolean[] {false, true}) {
            for (Map<String, Object> row : offerings) {
                if (Boolean.TRUE.equals(row.get("isBundle")) == bundlePass) {
                    n += createRemapped(catalogBase, cat + "/productOffering", dstTok, row, ids) != null ? 1 : 0;
                }
            }
        }
        counts.put("offerings", n);
        return counts;
    }

    /** The wholesale money-model travels with the clone: seeker AND provider
     *  rate cards, so negotiation twins run in-sandbox at real agreed rates. */
    private int copyRateCards(String srcTok, String dstTok) {
        int n = 0;
        String base = "/tmf-api/usageManagement/v4";
        for (String path : new String[] {"/wholesaleRateCard", "/providerRateCard"}) {
            for (Map<String, Object> row : fetchAll(usageBase, base + path, srcTok)) {
                n += createRemapped(usageBase, base + path, dstTok,
                        row, new java.util.HashMap<>()) != null ? 1 : 0;
            }
        }
        return n;
    }

    private int copyPolicyRules(String srcTok, String dstTok) {
        int n = 0;
        for (Map<String, Object> row : fetchAll(policyBase, "/tmf-api/policyManagement/v4/policyRule", srcTok)) {
            n += createRemapped(policyBase, "/tmf-api/policyManagement/v4/policyRule", dstTok,
                    row, new java.util.HashMap<>()) != null ? 1 : 0;
        }
        return n;
    }

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
