package com.bss.userroles.service;

import com.bss.userroles.dto.BaseImportReport;
import com.bss.userroles.dto.BrandPatch;
import com.bss.userroles.dto.BrandView;
import com.bss.userroles.dto.CloneReceipt;
import com.bss.userroles.dto.CloneReceipt.CopyCounts;
import com.bss.userroles.dto.CloneRequest;
import com.bss.userroles.dto.ClockReceipt;
import com.bss.userroles.dto.ImportBaseRequest;
import com.bss.userroles.dto.MutateReceipt;
import com.bss.userroles.dto.OnboardReceipt;
import com.bss.userroles.dto.OnboardRequest;
import com.bss.userroles.dto.OperatorPatch;
import com.bss.userroles.dto.OperatorView;
import com.bss.userroles.dto.ProspectSimulation;
import com.bss.userroles.dto.ProspectSimulationRequest;
import com.bss.userroles.dto.QuarterResult;
import com.bss.userroles.dto.QuarterResult.SimulatedQuarter;
import com.bss.userroles.dto.SeedTwinRequest;
import com.bss.userroles.dto.TwinBaseReceipt;
import com.bss.userroles.dto.UserView;
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
import java.security.SecureRandom;
import java.util.Base64;
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
    private static final SecureRandom RANDOM = new SecureRandom();
    /** The template's staff login is re-minted, never imported: same
     * username and dev password, a credential of this realm's own making. */
    private static final String STAFF_USERNAME = "demo";
    private static final String STAFF_PASSWORD = "demo";

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
    public List<OperatorView> list() {
        return tenants.getRegistry().stream().map(OperatorView::of).toList();
    }

    public OnboardReceipt onboard(OnboardRequest dto) throws Exception {
        // a nameless form must never mint a realm called "null"
        String id = dto.id() == null ? "" : dto.id().toLowerCase().trim();
        if (!SAFE_ID.matcher(id).matches() || "master".equals(id)) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "operator id: 3-21 chars, a-z0-9, starting with a letter");
        }
        String name = dto.name() == null ? id : dto.name();
        String locale = dto.locale() == null ? "en" : dto.locale();
        String currency = dto.currency() == null ? "EUR" : dto.currency();
        String color = dto.color() == null ? "#B85C38" : dto.color();
        long t0 = System.currentTimeMillis();

        String adminToken = masterAdminToken();
        // THIS operator's own machine credential — never the template's.
        // Generated here, written only into the realm and this tenant's
        // registry block; it is never logged and never returned.
        String machineSecret = newMachineSecret();
        // build the clone BEFORE deleting the old realm — a bad/stale
        // template must never destroy a serving realm
        String realmJson = realmClone(id, name, machineSecret);
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
        // the template's USERS never travel — its role BINDINGS do: each
        // client's own service account is re-granted the scopes the
        // template recorded for it, and a fresh staff login is minted.
        applyServiceAccountRoles(adminToken, id);
        mintStaffUser(adminToken, id);

        // the realm was replaced: any cached machine token for it is now a lie
        idp.evictTokens(id);
        appendTenantBlock(id, name, locale, currency, color, machineSecret);
        // this service joins its own fleet immediately; the rest follow
        // within one refresh interval
        refresher.refresh();

        seedCatalog(id, name, currency);
        long seconds = (System.currentTimeMillis() - t0) / 1000;
        log.info("operator '{}' ({}) is LIVE in {}s — no restart, no rebuild", name, id, seconds);
        return new OnboardReceipt(id, name, locale, currency, "shop." + id + ".localhost", seconds);
    }

    /**
     * LIVE MUTATION of a serving operator: brand, locale, currency, color
     * are rewritten in the tenant's block and the fleet's refreshers carry
     * them out within one interval — no restart. Identity (id, issuer,
     * key endpoints) is deliberately NOT editable here.
     */
    /** The caller's OWN brand card — the fields a hosted operator's
     *  marketing team may read: name, color, tagline. Nothing operational. */
    public BrandView brandOf(String id) throws Exception {
        String yml = Files.readString(Path.of(tenantsFile));
        Matcher m = Pattern.compile("(      - id: " + id + "\n(?:        .*\n)*)").matcher(yml);
        if (!m.find()) {
            throw new com.bss.userroles.exception.NotFoundException("Operator '" + id + "' not found");
        }
        String block = m.group(1);
        return new BrandView(id, firstGroup(block, "brand-name: (.*)"),
                strip(firstGroup(block, "brand-color: (.*)")), strip(firstGroup(block, "tagline: (.*)")));
    }

    /** Brand-only mutation for the tenant's own team: name, color, tagline —
     *  locale, currency and agent-commerce stay with the host operator. */
    public MutateReceipt mutateBrand(String id, BrandPatch dto) throws Exception {
        if (dto.isEmpty()) {
            return new MutateReceipt(id, false);
        }
        return mutate(id, OperatorPatch.brand(dto));
    }

    private static String firstGroup(String block, String regex) {
        Matcher m = Pattern.compile(regex).matcher(block);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String strip(String v) {
        return v != null && v.length() > 1 && v.startsWith("\"") && v.endsWith("\"")
                ? v.substring(1, v.length() - 1) : v;
    }

    public MutateReceipt mutate(String id, OperatorPatch dto) throws Exception {
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
        if (dto.name() != null) {
            block = block.replaceAll("brand-name: .*", "brand-name: " + dto.name());
        }
        if (dto.color() != null) {
            block = block.replaceAll("brand-color: .*", "brand-color: \"" + dto.color() + "\"");
        }
        if (dto.locale() != null) {
            block = block.replaceAll("locale: .*", "locale: \"" + dto.locale() + "\"");
        }
        if (dto.currency() != null) {
            block = block.replaceAll("currency: .*", "currency: " + dto.currency());
        }
        if (dto.catalogGovernance() != null) {
            String mode = dto.catalogGovernance().trim();
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
        if (dto.priceParityMode() != null) {
            String mode = dto.priceParityMode().trim();
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
        if (dto.tagline() != null) {
            // the storefront hero line — free text, so it rides YML double-quoted;
            // insert-if-absent because older tenant blocks predate the field
            String tagline = dto.tagline().replace("\"", "'").trim();
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
        if (dto.agentCommerce() != null) {
            // The agentic-commerce switch: how much of this operator AI
            // shopping agents may see. Flipping it here live-refreshes the
            // gateway's gate — reversible in one refresh interval.
            String mode = dto.agentCommerce();
            if (!java.util.Set.of("off", "discovery", "full").contains(mode)) {
                throw new com.bss.userroles.exception.BadRequestException(
                        "agentCommerce must be off, discovery or full");
            }
            block = block.replaceAll("agent-commerce: .*", "agent-commerce: \"" + mode + "\"");
        }
        Files.writeString(Path.of(tenantsFile), yml.replace(m.group(1), block));
        refresher.refresh();
        log.info("operator '{}' mutated LIVE — the fleet follows within one refresh interval", id);
        return new MutateReceipt(id, true);
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

    /**
     * A NEW OPERATOR'S CREDENTIAL IS ITS OWN. 48 bytes from a
     * {@link SecureRandom}, URL-safe base64 so it survives a YAML scalar
     * and the registry's {@code ${ENV:default}} placeholder grammar
     * (which ends the default at the first '}').
     */
    private static String newMachineSecret() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * nova's realm SHAPE with this operator's identity — and nothing of
     * nova's that is a credential. Hosts re-pointed, object ids stripped
     * so the clone mints its own, every confidential client's template
     * {@code secret} replaced by THIS tenant's freshly generated one, and
     * the template's users dropped whole: no persona, no password hash and
     * no service-account record crosses a tenant boundary. The role
     * bindings those user entries carried are re-applied against the new
     * realm's own accounts after the import (see
     * {@link #applyServiceAccountRoles} and {@link #mintStaffUser}).
     */
    String realmClone(String id, String name, String machineSecret) throws Exception {
        String raw = Files.readString(Path.of(templatePath));
        for (String channel : List.of("shop", "csr", "console", "biz")) {
            raw = raw.replace(channel + ".nova.localhost", channel + "." + id + ".localhost");
        }
        ObjectNode realm = (ObjectNode) JSON.readTree(raw);
        realm.put("realm", id);
        realm.put("displayName", name);
        for (JsonNode c : realm.withArray("clients")) {
            ObjectNode client = (ObjectNode) c;
            if (client.path("publicClient").asBoolean(false)) {
                client.remove("secret");
            } else {
                client.put("secret", machineSecret);
            }
        }
        realm.set("users", JSON.createArrayNode());
        stripIds(realm);
        String json = JSON.writeValueAsString(realm);
        assertNoInheritedCredentials(realm, machineSecret);
        return json;
    }

    /** The invariant both onboarding paths owe the threat model: a clone
     *  leaves here with no template secret and no template user. */
    private void assertNoInheritedCredentials(ObjectNode realm, String machineSecret) throws Exception {
        ObjectNode template = (ObjectNode) JSON.readTree(Files.readString(Path.of(templatePath)));
        java.util.Set<String> templateSecrets = new java.util.HashSet<>();
        for (JsonNode c : template.withArray("clients")) {
            if (c.hasNonNull("secret")) {
                templateSecrets.add(c.get("secret").asText());
            }
        }
        for (JsonNode c : realm.withArray("clients")) {
            String secret = c.path("secret").asText(null);
            if (secret != null && !secret.equals(machineSecret)) {
                throw new IllegalStateException("clone kept a template secret on client "
                        + c.path("clientId").asText());
            }
            if (secret != null && templateSecrets.contains(secret)) {
                throw new IllegalStateException("generated secret collides with the template's");
            }
        }
        if (!realm.withArray("users").isEmpty()) {
            throw new IllegalStateException("clone carries template users");
        }
    }

    /**
     * Each client's service account is created by Keycloak with the client;
     * only its SCOPES have to be restored. The template's user entries are
     * read here as configuration — which realm roles a given
     * {@code serviceAccountClientId} needs — never imported as accounts.
     */
    private void applyServiceAccountRoles(String adminToken, String id) throws Exception {
        ObjectNode template = (ObjectNode) JSON.readTree(Files.readString(Path.of(templatePath)));
        Map<String, JsonNode> realmRoles = rolesByName(adminToken, id, "/roles");
        int bound = 0;
        for (JsonNode u : template.withArray("users")) {
            String clientId = u.path("serviceAccountClientId").asText(null);
            if (clientId == null) {
                continue;
            }
            String clientUuid = clientUuid(adminToken, id, clientId);
            if (clientUuid == null) {
                continue;
            }
            JsonNode account = adminGet(adminToken,
                    "/admin/realms/" + id + "/clients/" + clientUuid + "/service-account-user");
            if (account == null || account.path("id").asText(null) == null) {
                continue;
            }
            String userId = account.get("id").asText();
            // THE DEFAULT-ROLES TRAP: a realm IMPORT gives a service account
            // exactly the roles its user entry lists; an account Keycloak
            // creates itself also carries default-roles-<realm>, whose
            // composite includes `customer`. A machine token with `customer`
            // is scoped to a party it does not have, and every callback the
            // fleet makes for that tenant comes back 404. Match the import.
            JsonNode defaults = realmRoles.get("default-roles-" + id);
            if (defaults != null) {
                adminDelete(adminToken, "/admin/realms/" + id + "/users/" + userId
                        + "/role-mappings/realm", JSON.createArrayNode().add(defaults));
            }
            grantRealmRoles(adminToken, id, userId, u.path("realmRoles"), realmRoles);
            JsonNode clientRoles = u.path("clientRoles");
            Iterator<String> owners = clientRoles.fieldNames();
            while (owners.hasNext()) {
                String owner = owners.next();
                String ownerUuid = clientUuid(adminToken, id, owner);
                if (ownerUuid == null) {
                    continue;
                }
                Map<String, JsonNode> ownerRoles = rolesByName(adminToken, id,
                        "/clients/" + ownerUuid + "/roles");
                ArrayNode wanted = JSON.createArrayNode();
                for (JsonNode role : clientRoles.get(owner)) {
                    JsonNode rep = ownerRoles.get(role.asText());
                    if (rep != null) {
                        wanted.add(rep);
                    }
                }
                if (!wanted.isEmpty()) {
                    adminPost(adminToken, "/admin/realms/" + id + "/users/" + userId
                            + "/role-mappings/clients/" + ownerUuid, wanted);
                }
            }
            bound++;
        }
        log.info("realm '{}': {} service accounts re-scoped from the template's role bindings", id, bound);
    }

    /**
     * The operator's first staff login, MINTED for this realm — same
     * username and dev password the fleet expects, a credential this
     * realm made rather than one inherited with another tenant's hash.
     * Goes through partialImport because the admin user endpoint and the
     * realm import disagree about {@code registrationEmailAsUsername}.
     */
    private void mintStaffUser(String adminToken, String id) throws Exception {
        ObjectNode template = (ObjectNode) JSON.readTree(Files.readString(Path.of(templatePath)));
        ArrayNode roles = JSON.createArrayNode();
        for (JsonNode u : template.withArray("users")) {
            if (STAFF_USERNAME.equals(u.path("username").asText())) {
                for (JsonNode role : u.path("realmRoles")) {
                    roles.add(role.asText());
                }
            }
        }
        ObjectNode staff = JSON.createObjectNode();
        staff.put("username", STAFF_USERNAME);
        staff.put("enabled", true);
        staff.put("email", "demo@bss.local");
        staff.put("emailVerified", true);
        staff.put("firstName", "Demo");
        staff.put("lastName", "User");
        staff.set("requiredActions", JSON.createArrayNode());
        ObjectNode credential = JSON.createObjectNode();
        credential.put("type", "password");
        credential.put("value", STAFF_PASSWORD);
        credential.put("temporary", false);
        staff.set("credentials", JSON.createArrayNode().add(credential));
        staff.set("realmRoles", roles);
        ObjectNode body = JSON.createObjectNode();
        body.put("ifResourceExists", "OVERWRITE");
        body.set("users", JSON.createArrayNode().add(staff));
        adminPost(adminToken, "/admin/realms/" + id + "/partialImport", body);
        log.info("realm '{}': staff login minted with {} roles", id, roles.size());
    }

    private Map<String, JsonNode> rolesByName(String adminToken, String id, String path) {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        JsonNode roles = adminGet(adminToken,
                "/admin/realms/" + id + path + "?briefRepresentation=true&max=2000");
        if (roles != null) {
            for (JsonNode r : roles) {
                ObjectNode rep = JSON.createObjectNode();
                rep.put("id", r.path("id").asText());
                rep.put("name", r.path("name").asText());
                byName.put(r.path("name").asText(), rep);
            }
        }
        return byName;
    }

    private void grantRealmRoles(String adminToken, String id, String userId, JsonNode wantedNames,
            Map<String, JsonNode> realmRoles) {
        ArrayNode wanted = JSON.createArrayNode();
        for (JsonNode role : wantedNames) {
            JsonNode rep = realmRoles.get(role.asText());
            if (rep != null) {
                wanted.add(rep);
            }
        }
        if (!wanted.isEmpty()) {
            adminPost(adminToken, "/admin/realms/" + id + "/users/" + userId + "/role-mappings/realm", wanted);
        }
    }

    private String clientUuid(String adminToken, String id, String clientId) {
        JsonNode found = adminGet(adminToken, "/admin/realms/" + id + "/clients?clientId=" + clientId);
        return found != null && !found.isEmpty() ? found.get(0).path("id").asText(null) : null;
    }

    private JsonNode adminGet(String adminToken, String path) {
        try {
            return rest.get().uri(keycloakBase + path)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.warn("admin GET {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    private void adminDelete(String adminToken, String path, JsonNode body) {
        try {
            rest.method(org.springframework.http.HttpMethod.DELETE).uri(keycloakBase + path)
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .body(body).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("admin DELETE {} failed: {}", path, e.getMessage());
        }
    }

    private void adminPost(String adminToken, String path, JsonNode body) {
        try {
            rest.post().uri(keycloakBase + path)
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .body(body).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("admin POST {} failed: {}", path, e.getMessage());
        }
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
     * entry re-suffixed for the newborn, backchannel pinned in-network,
     * and THIS tenant's own machine credential in place of the shared
     * {@code ${OIDC_CLIENT_SECRET:...}} every service resolves from its
     * own env (which is what made every realm's clients interchangeable). */
    void appendTenantBlock(String id, String name, String locale, String currency,
            String color, String machineSecret) throws Exception {
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
                .replaceAll("ai-visibility: .*", "ai-visibility: \"search-only\"")
                // the credential wall: this operator's clients answer to a
                // secret no other operator's realm has ever seen. The env
                // name lets a deployment move it to a secret store without
                // editing the file.
                .replaceAll("machine-client-secret: .*", Matcher.quoteReplacement(
                        "machine-client-secret: ${OIDC_CLIENT_SECRET_" + id.toUpperCase()
                                + ":" + machineSecret + "}"));
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
    public CloneReceipt cloneOperator(String sourceId, CloneRequest dto) throws Exception {
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
        String id = dto.id() == null ? null : dto.id().toLowerCase().trim();
        String name = dto.name() == null ? strip(firstGroup(block, "brand-name: (.*)")) + " Sandbox" : dto.name();
        OnboardReceipt made = onboard(OnboardRequest.of(id, name,
                strip(orDefault(firstGroup(block, "locale: (.*)"), "en")),
                strip(orDefault(firstGroup(block, "currency: (.*)"), "EUR")),
                strip(orDefault(firstGroup(block, "brand-color: (.*)"), "#B85C38"))));
        markSandbox(made.id());
        refresher.refresh();
        String srcTok = staffToken(srcRealm);
        String dstTok = staffToken(made.id());
        waitAdopt(catalogBase, "/tmf-api/productCatalogManagement/v4/productOffering", dstTok);
        waitAdopt(policyBase, "/tmf-api/policyManagement/v4/policyRule", dstTok);
        waitAdopt(usageBase, "/tmf-api/usageManagement/v4/wholesaleRateCard", dstTok);
        CopyCounts copied = copyCatalog(srcTok, dstTok)
                .withRules(copyPolicyRules(srcTok, dstTok), copyRateCards(srcTok, dstTok));
        log.info("sandbox clone '{}' of '{}' is LIVE — copied {}", made.id(), sourceId, copied);
        return new CloneReceipt(made, sourceId, true, copied);
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
    public TwinBaseReceipt seedTwinBase(String cloneId, SeedTwinRequest dto) throws Exception {
        String yml = Files.readString(Path.of(tenantsFile));
        Matcher cm = Pattern.compile("(      - id: " + cloneId + "\n(?:        .*\n)*)").matcher(yml);
        if (!cm.find() || !cm.group(1).contains("sandbox:")) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "twin seeding is sandbox-only — '" + cloneId + "' is not a sandbox clone");
        }
        String sourceId = dto.sourceId() == null ? "genalpha" : dto.sourceId();
        Matcher sm = Pattern.compile("(      - id: " + sourceId + "\n(?:        .*\n)*)").matcher(yml);
        if (!sm.find()) {
            throw new com.bss.userroles.exception.BadRequestException("unknown source '" + sourceId + "'");
        }
        String srcRealm = orDefault(firstGroup(sm.group(1), "issuer: .*?/realms/([a-z0-9-]+)"), sourceId);
        int cap = dto.count() == null ? 25 : dto.count();
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
        log.info("twin base seeded into sandbox '{}': {} subscribers shaped like '{}'",
                cloneId, seeded, sourceId);
        return new TwinBaseReceipt(cloneId, sourceId, seeded, seededBy,
                "only the AGGREGATE offering distribution was read from the source — "
                + "no name, email or id crossed; every subscriber here is fictional by construction");
    }

    /** T1 — advance a SANDBOX clone's clock (clock-offset-days in its block). */
    public ClockReceipt advanceClock(String cloneId, int days) throws Exception {
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
        return new ClockReceipt(cloneId, next);
    }

    /** T1 — the simulated quarter: 3 x (advance 30 days -> REAL billing run).
     *  Recurring charges only until T3; the report says so on its face. */
    public SimulatedQuarter simulateQuarter(String cloneId) throws Exception {
        String dstTok = staffToken(cloneId);
        java.util.List<QuarterResult.Cycle> cycles = new java.util.ArrayList<>();
        for (int cycle = 1; cycle <= 3; cycle++) {
            int offset = advanceClock(cloneId, 30).clockOffsetDays();
            try {
                // the fleet learns the new clock on its refresh tick — outwait it
                Thread.sleep(35000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            // billing's own answer rides the report as its document; a refusal as {"error": …}
            JsonNode run;
            try {
                run = rest.post().uri(billingBase + "/tmf-api/customerBillManagement/v4/billingRun")
                        .header("Authorization", "Bearer " + dstTok)
                        .header("Content-Type", "application/json")
                        .body(Map.of()).retrieve().body(JsonNode.class);
            } catch (Exception e) {
                run = JSON.createObjectNode().put("error", String.valueOf(e.getMessage()));
            }
            cycles.add(new QuarterResult.Cycle(cycle, offset, run));
        }
        return SimulatedQuarter.of(cloneId, cycles);
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
    public ProspectSimulation prospectSimulation(ProspectSimulationRequest dto) throws Exception {
        List<ProspectSimulationRequest.PriceRow> priceList = dto.priceList();
        if (priceList == null || priceList.isEmpty()) {
            throw new com.bss.userroles.exception.BadRequestException(
                    "priceList [{offeringName, monthly}] is required — the prospect's PUBLIC tariffs");
        }
        String id = dto.id() == null
                ? "ps" + String.valueOf(System.currentTimeMillis()).substring(7)
                : dto.id();
        String name = dto.name() == null ? "Prospect" : dto.name();
        String currency = dto.currency() == null ? "EUR" : dto.currency();
        OnboardReceipt made = onboard(OnboardRequest.of(id, name + " (simulation)",
                dto.locale() == null ? "en" : dto.locale(), currency, null));
        id = made.id();
        markSandbox(id);
        refresher.refresh();
        String tok = staffToken(id);
        waitAdopt(catalogBase, "/tmf-api/productCatalogManagement/v4/productOffering", tok);

        // the shelf, from the PUBLIC price list — categorized, because the
        // storefront's line-of-business tabs hide category-less offerings
        String categoryName = dto.category() == null ? "Broadband" : dto.category();
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
        for (ProspectSimulationRequest.PriceRow row : priceList) {
            String offName = String.valueOf(row.offeringName());
            java.math.BigDecimal monthly = row.monthly() == null ? java.math.BigDecimal.ZERO : row.monthly();
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
        if (dto.baseMix() != null) {
            waitAdopt(partyBase, "/tmf-api/party/v4/individual", tok);
            waitAdopt(inventoryBase, "/tmf-api/productInventory/v4/product", tok);
            for (ProspectSimulationRequest.MixRow row : dto.baseMix()) {
                String offName = String.valueOf(row.offeringName());
                String offId = offeringByName.get(offName);
                if (offId == null) {
                    continue;
                }
                int n = row.subscribers() == null ? 0 : row.subscribers();
                for (int i = 0; i < n; i++) {
                    seeded += mintTwin(tok, offName, offId, stamp, i) ? 1 : 0;
                }
            }
        }

        // the quarter, on the real engines
        QuarterResult quarter = seeded > 0 ? simulateQuarter(id) : QuarterResult.Skipped.NO_BASE;
        return ProspectSimulation.of(id, name, offeringByName.size(), seeded, quarter);
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
    public BaseImportReport importBase(String tenantId, ImportBaseRequest dto) throws Exception {
        List<ImportBaseRequest.Row> rows = dto.rows();
        if (rows == null || rows.isEmpty()) {
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

        java.util.List<BaseImportReport.ImportedCustomer> imported = new java.util.ArrayList<>();
        java.util.List<String> alreadyPresent = new java.util.ArrayList<>();
        java.util.List<BaseImportReport.MissingOffering> offeringMissing = new java.util.ArrayList<>();
        java.util.List<BaseImportReport.FailedRow> failed = new java.util.ArrayList<>();
        for (ImportBaseRequest.Row row : rows) {
            String externalRef = row.externalRef();
            String email = row.email();
            String offeringName = row.offeringName();
            Map<String, Object> offering = shelf.get(offeringName);
            if (offering == null) {
                offeringMissing.add(new BaseImportReport.MissingOffering(externalRef, offeringName,
                        "no offering with this name in the target catalog — author it first"));
                continue;
            }
            UserView login;
            try {
                login = rest.post().uri(self + "/tmf-api/rolesAndPermissionsManagement/v4/user")
                        .header("Authorization", "Bearer " + tok)
                        .header("Content-Type", "application/json")
                        .body(new com.bss.userroles.dto.CreateUserRequest(email,
                                row.givenName() == null ? "Imported" : row.givenName(),
                                row.familyName() == null ? externalRef : row.familyName()))
                        .retrieve().body(UserView.class);
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
                        "id", login.id(), "role", "customer", "@referredType", "Individual")));
                if (row.msisdn() != null) {
                    product.put("supportingResource", java.util.List.of(Map.of("value", row.msisdn())));
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
                        .body(Map.of("ownerPartyId", login.id(), "name", offeringName,
                                "msisdn", row.msisdn() == null ? "" : row.msisdn()))
                        .retrieve().body(Map.class);
                imported.add(new BaseImportReport.ImportedCustomer(externalRef, login.id(), email,
                        login.temporaryPassword(), offeringName, row.msisdn()));
            } catch (Exception e) {
                failed.add(new BaseImportReport.FailedRow(externalRef, String.valueOf(e.getMessage())));
            }
        }
        log.info("base import into '{}': {} imported, {} already present, {} missing offerings, {} failed",
                tenantId, imported.size(), alreadyPresent.size(), offeringMissing.size(), failed.size());
        return BaseImportReport.of(tenantId, rows.size(), imported, alreadyPresent, offeringMissing, failed);
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

    private CopyCounts copyCatalog(String srcTok, String dstTok) {
        String cat = "/tmf-api/productCatalogManagement/v4";
        Map<String, String> ids = new java.util.HashMap<>();
        int categories = 0;
        for (Map<String, Object> row : fetchAll(catalogBase, cat + "/category", srcTok)) {
            categories += createRemapped(catalogBase, cat + "/category", dstTok, row, ids) != null ? 1 : 0;
        }
        int specifications = 0;
        for (Map<String, Object> row : fetchAll(catalogBase, cat + "/productSpecification", srcTok)) {
            specifications += createRemapped(catalogBase, cat + "/productSpecification", dstTok, row, ids) != null
                    ? 1 : 0;
        }
        int prices = 0;
        for (Map<String, Object> row : fetchAll(catalogBase, cat + "/productOfferingPrice", srcTok)) {
            prices += createRemapped(catalogBase, cat + "/productOfferingPrice", dstTok, row, ids) != null ? 1 : 0;
        }
        // leaves before bundles: a bundle's children must already exist to remap
        java.util.List<Map<String, Object>> offerings = fetchAll(catalogBase, cat + "/productOffering", srcTok);
        int offeringCount = 0;
        for (boolean bundlePass : new boolean[] {false, true}) {
            for (Map<String, Object> row : offerings) {
                if (Boolean.TRUE.equals(row.get("isBundle")) == bundlePass) {
                    offeringCount += createRemapped(catalogBase, cat + "/productOffering", dstTok, row, ids) != null
                            ? 1 : 0;
                }
            }
        }
        return new CopyCounts(categories, specifications, prices, offeringCount, 0, 0);
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
