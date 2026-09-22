package com.bss.party.privacy;

import com.bss.party.entity.ErasureRecord;
import com.bss.party.entity.Individual;
import com.bss.party.repository.ErasureRecordRepository;
import com.bss.party.repository.IndividualRepository;
import com.bss.party.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE DATA PASSPORT AND THE ERASER. Export aggregates a person's data
 * across the fleet — riding the CALLER's own token, so a person can only
 * ever export what their credentials could read (the right of access as
 * no-new-authority). Erasure fans out to every service that holds
 * personal data, disables the login at the IdP, anonymizes the profile
 * in place — and REFUSES two things honestly: erasing a subscriber with
 * active services (terminate first — performance of contract), and
 * touching the categories the bookkeeping law holds (bills, payments,
 * orders, usage, agreements — named in the report WITH their basis).
 * The report itself becomes an immutable audit row: proof the right was
 * honoured, holding no personal data beyond the party reference.
 */
@Service
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    /** category → legal basis for retention (reported, never deleted here) */
    private static final Map<String, String> RETAINED = retained();

    private static Map<String, String> retained() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bills", "bookkeeping law — retained 5 years from fiscal year end (GDPR Art. 17(3)(b))");
        m.put("payments", "bookkeeping law — retained with the bills they settle");
        m.put("orders", "contract records — retained for the limitation period");
        m.put("usage", "billing evidence — retained with the bills it rated");
        m.put("agreements", "contract records — retained for the limitation period");
        return Collections.unmodifiableMap(m);
    }

    private final IndividualRepository individuals;
    private final ErasureRecordRepository erasures;
    private final TenantScope tenantScope;
    private final ObjectMapper json;
    private final RestClient rest;
    private final List<Sibling> siblings = new ArrayList<>();
    private final String inventoryBase;

    record Sibling(String category, String baseUrl) {
    }

    public PrivacyService(IndividualRepository individuals, ErasureRecordRepository erasures,
            TenantScope tenantScope, ObjectMapper json, RestClient.Builder builder,
            @Value("${bss.privacy.interaction-url:}") String interactionUrl,
            @Value("${bss.privacy.communication-url:}") String communicationUrl,
            @Value("${bss.privacy.campaign-url:}") String campaignUrl,
            @Value("${bss.privacy.cart-url:}") String cartUrl,
            @Value("${bss.privacy.ticket-url:}") String ticketUrl,
            @Value("${bss.privacy.appointment-url:}") String appointmentUrl,
            @Value("${bss.privacy.insight-url:}") String insightUrl,
            @Value("${bss.privacy.user-roles-url:}") String userRolesUrl,
            @Value("${bss.privacy.inventory-url:}") String inventoryUrl) {
        this.individuals = individuals;
        this.erasures = erasures;
        this.tenantScope = tenantScope;
        this.json = json;
        this.rest = builder.build();
        addSibling("interactions", interactionUrl);
        addSibling("messages", communicationUrl);
        addSibling("marketing", campaignUrl);
        addSibling("carts", cartUrl);
        addSibling("tickets", ticketUrl);
        addSibling("appointments", appointmentUrl);
        addSibling("behavioral", insightUrl);
        addSibling("identity", userRolesUrl);
        this.inventoryBase = inventoryUrl;
    }

    private void addSibling(String category, String url) {
        if (url != null && !url.isBlank()) {
            siblings.add(new Sibling(category, url));
        }
    }

    /** The passport: one JSON, a category per shelf, the caller's token
     * doing all the talking. Each sibling's shelf is its own document. */
    public PrivacyPassport export(String partyId, String bearer) {
        String tenant = tenantScope.currentTenantId();
        PrivacyPassport.Profile profile = individuals.findByIdAndTenantId(partyId, tenant)
                .<PrivacyPassport.Profile>map(this::profileOf).orElse(PrivacyPassport.NoProfile.NONE);
        List<JsonNode> categories = new ArrayList<>();
        for (Sibling sibling : siblings) {
            if ("identity".equals(sibling.category())) {
                continue; // the IdP slice has no export endpoint — login metadata only
            }
            try {
                JsonNode slice = rest.get()
                        .uri(sibling.baseUrl() + "/privacy/v1/export?partyId=" + partyId)
                        .header("Authorization", bearer)
                        .retrieve().body(JsonNode.class);
                categories.add(slice);
            } catch (RuntimeException unavailable) {
                categories.add(json.valueToTree(CategoryReceipt.exportUnavailable(sibling.category())));
            }
        }
        return new PrivacyPassport(partyId, OffsetDateTime.now().toString(), profile, categories, RETAINED);
    }

    /** The eraser: refuse-if-active, fan out, kill the login, anonymize
     * the profile, write the audit. */
    @Transactional
    public ErasureReport erase(String partyId, String bearer, String executedBy) {
        String tenant = tenantScope.currentTenantId();
        Individual person = individuals.findByIdAndTenantId(partyId, tenant)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (hasActiveProducts(partyId, bearer)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "the party holds ACTIVE services — terminate them first; "
                    + "erasure cannot break a running contract (GDPR Art. 17(3)(b))");
        }
        List<JsonNode> categories = new ArrayList<>();
        boolean partial = false;
        for (Sibling sibling : siblings) {
            try {
                JsonNode result = rest.post().uri(sibling.baseUrl() + "/privacy/v1/erase")
                        .header("Authorization", bearer)
                        .header("Content-Type", "application/json")
                        .body(new EraseRequest(partyId))
                        .retrieve().body(JsonNode.class);
                categories.add(result);
            } catch (RuntimeException unavailable) {
                partial = true;
                categories.add(json.valueToTree(CategoryReceipt.eraseUnavailable(sibling.category())));
                log.warn("erasure fan-out failed for {}: {}", sibling.category(),
                        unavailable.getMessage());
            }
        }
        // the profile: anonymized IN PLACE — the id survives as the
        // pseudonymous reference every retained record points at
        person.setGivenName("Erased");
        person.setFamilyName("Erased");
        person.setContactMediumJson("[]");
        person.setBirthDate(null);
        individuals.save(person);
        categories.add(json.valueToTree(CategoryReceipt.profileAnonymized()));
        for (Map.Entry<String, String> held : RETAINED.entrySet()) {
            categories.add(json.valueToTree(CategoryReceipt.retained(held.getKey(), held.getValue())));
        }
        ErasureReport report = new ErasureReport(partyId,
                partial ? "partial — re-run required" : "completed",
                executedBy, OffsetDateTime.now().toString(), categories, null);
        ErasureRecord record = new ErasureRecord();
        record.setId(UUID.randomUUID().toString());
        record.setTenantId(tenant);
        record.setPartyId(partyId);
        record.setExecutedBy(executedBy);
        record.setExecutedAt(OffsetDateTime.now());
        record.setReportJson(writeJson(report));
        erasures.save(record);
        return report.withAudit(record.getId());
    }

    public List<ErasureAuditRow> auditTrail() {
        return erasures.findTop50ByTenantIdOrderByExecutedAtDesc(tenantScope.currentTenantId())
                .stream().map(ErasureAuditRow::of).toList();
    }

    /** Erasure's honest precondition: no running contract. Walks the
     * inventory pages with the CALLER's token. Fail-CLOSED: if the
     * inventory cannot be read, erasure refuses rather than guesses. */
    private boolean hasActiveProducts(String partyId, String bearer) {
        if (inventoryBase == null || inventoryBase.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "inventory unreachable — cannot prove the party has no active services");
        }
        try {
            for (int offset = 0; offset < 100_000; offset += 100) {
                JsonNode page = rest.get()
                        .uri(inventoryBase + "/tmf-api/productInventory/v4/product?limit=100&offset=" + offset)
                        .header("Authorization", bearer)
                        .retrieve().body(JsonNode.class);
                if (page == null || !page.isArray() || page.isEmpty()) {
                    return false;
                }
                for (JsonNode product : page) {
                    if (!"active".equalsIgnoreCase(product.path("status").asText())) {
                        continue;
                    }
                    for (JsonNode related : product.path("relatedParty")) {
                        if (partyId.equals(related.path("id").asText())) {
                            return true;
                        }
                    }
                }
                if (page.size() < 100) {
                    return false;
                }
            }
            return false;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException unreachable) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "inventory unreachable — cannot prove the party has no active services");
        }
    }

    private PrivacyPassport.ProfileView profileOf(Individual person) {
        return PrivacyPassport.ProfileView.of(person, readJson(person.getContactMediumJson()));
    }

    private JsonNode readJson(String raw) {
        try {
            return raw == null ? json.createArrayNode() : json.readTree(raw);
        } catch (Exception e) {
            return json.createArrayNode();
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
