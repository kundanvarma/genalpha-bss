package com.bss.basemigration.service;

import com.bss.basemigration.client.AgreementClient;
import com.bss.basemigration.client.InventoryClient;
import com.bss.basemigration.client.PartyClient;
import com.bss.basemigration.entity.MigrationCustomer;
import com.bss.basemigration.entity.MigrationPlan;
import com.bss.basemigration.events.DomainEventPublisher;
import com.bss.basemigration.repository.MigrationCustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who does this plan actually touch? On arm (and on trigger scans) the
 * ACTIVE base on each source offering is pulled from inventory and pushed
 * through the eligibility rules: grandfather lists skip, segment excludes
 * skip, and an unexpired commitment is treated per the plan — deferred to
 * its own end, excluded, or migrated with the penalty-free flag already
 * set. The binding lookup fails CLOSED: better an aborted arm than a
 * bound customer moved blind.
 */
@Service
public class CandidateDiscovery {

    private static final Logger log = LoggerFactory.getLogger(CandidateDiscovery.class);

    public static final String IN_BINDING_DEFER = "defer-to-expiry";
    public static final String IN_BINDING_EXCLUDE = "exclude";
    public static final String IN_BINDING_FREE_EXIT = "free-exit";

    private final InventoryClient inventory;
    private final AgreementClient agreements;
    private final PartyClient parties;
    private final MigrationCustomerRepository customers;
    private final DomainEventPublisher events;
    private final Json json;
    private final Clock clock;

    public CandidateDiscovery(InventoryClient inventory, AgreementClient agreements,
            PartyClient parties, MigrationCustomerRepository customers,
            DomainEventPublisher events, Json json, Clock clock) {
        this.inventory = inventory;
        this.agreements = agreements;
        this.parties = parties;
        this.customers = customers;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** Sweep the whole active base against the matrix (bulk arm, promo roll-off). */
    public int discoverBulk(MigrationPlan plan, boolean courtesy, OffsetDateTime scheduledFor) {
        List<Map<String, Object>> base = inventory.listActiveProducts();
        int created = 0;
        for (Map<String, Object> row : json.readList(plan.getMatrixJson())) {
            String source = String.valueOf(row.get("sourceOfferingId"));
            for (Map<String, Object> product : base) {
                if (!(product.get("productOffering") instanceof Map<?, ?> ref)
                        || !source.equals(String.valueOf(ref.get("id")))) {
                    continue;
                }
                if (candidate(plan, row, product, courtesy, scheduledFor)) {
                    created++;
                }
            }
        }
        return created;
    }

    /** One party's products against the matrix (the age trigger works per birthday). */
    public int discoverForParty(MigrationPlan plan, String partyId, OffsetDateTime scheduledFor) {
        List<Map<String, Object>> owned = inventory.activeProductsOf(partyId);
        int created = 0;
        for (Map<String, Object> row : json.readList(plan.getMatrixJson())) {
            String source = String.valueOf(row.get("sourceOfferingId"));
            for (Map<String, Object> product : owned) {
                if (!(product.get("productOffering") instanceof Map<?, ?> ref)
                        || !source.equals(String.valueOf(ref.get("id")))) {
                    continue;
                }
                if (candidate(plan, row, product, false, scheduledFor)) {
                    created++;
                }
            }
        }
        return created;
    }

    /** Apply eligibility to one (product, matrix row); create the journey row when it passes. */
    private boolean candidate(MigrationPlan plan, Map<String, Object> matrixRow,
            Map<String, Object> product, boolean courtesy, OffsetDateTime scheduledFor) {
        String productId = String.valueOf(product.get("id"));
        String partyId = customerPartyIn(product);
        if (partyId == null
                || customers.existsByTenantIdAndPlanIdAndProductId(plan.getTenantId(), plan.getId(), productId)) {
            return false;
        }
        Map<String, Object> eligibility = json.readMap(plan.getEligibilityJson());
        if (listOf(eligibility.get("grandfatherPartyIds")).contains(partyId)
                || json.readStrings(plan.getGrandfatheredJson()).contains(partyId)) {
            return false;
        }
        List<String> segmentExcludes = listOf(eligibility.get("segmentExcludes"));
        if (!segmentExcludes.isEmpty() && excludedBySegment(partyId, segmentExcludes)) {
            return false;
        }

        String deltaClass = matrixRow.get("deltaClass") == null
                ? "neutral" : String.valueOf(matrixRow.get("deltaClass"));
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime effectiveSchedule = scheduledFor == null ? now : scheduledFor;
        boolean penaltyFreeExit = false;

        String sourceOfferingId = String.valueOf(matrixRow.get("sourceOfferingId"));
        Optional<OffsetDateTime> binding = agreements.commitmentEnd(partyId, sourceOfferingId, now);
        if (binding.isPresent()) {
            String inBinding = eligibility.get("inBinding") == null
                    ? IN_BINDING_DEFER : String.valueOf(eligibility.get("inBinding"));
            switch (inBinding) {
                case IN_BINDING_EXCLUDE -> {
                    return false;
                }
                case IN_BINDING_FREE_EXIT ->
                    // migrate now; a not-exclusively-beneficial change while bound
                    // grants the penalty-free door (EECC Art. 105)
                    penaltyFreeExit = "detrimental".equals(deltaClass);
                default ->
                    // defer-to-expiry: the notice waits for the commitment's own end
                    effectiveSchedule = binding.get().isAfter(effectiveSchedule)
                            ? binding.get() : effectiveSchedule;
            }
        }

        MigrationCustomer row = new MigrationCustomer();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(plan.getTenantId());
        row.setPlanId(plan.getId());
        row.setPartyId(partyId);
        row.setProductId(productId);
        row.setSourceOfferingId(sourceOfferingId);
        row.setSourceOfferingName(nameOf(product.get("productOffering")));
        row.setTargetOfferingId(String.valueOf(matrixRow.get("targetOfferingId")));
        row.setTargetOfferingName(matrixRow.get("targetOfferingName") == null
                ? null : String.valueOf(matrixRow.get("targetOfferingName")));
        row.setDeltaClass(deltaClass);
        row.setState(MigrationCustomer.SCHEDULED);
        row.setScheduledFor(effectiveSchedule);
        row.setPenaltyFreeExit(penaltyFreeExit);
        row.setExitRight(!courtesy && exitRightFor(plan, deltaClass));
        row.setSnapshotJson(json.write(snapshotOf(product)));
        row.setCreatedAt(now);
        row.setLastUpdate(now);
        customers.save(row);
        events.publish("CustomerMigrationScheduledEvent", "migrationCustomer",
                MigrationEngine.customerResource(row), plan.getTenantId());
        log.info("plan '{}': scheduled {} ({} -> {}) for {}", plan.getName(), partyId,
                row.getSourceOfferingName(), row.getTargetOfferingId(), effectiveSchedule);
        return true;
    }

    /** The exit right by delta class — the jurisdiction pack may override the
     *  default (detrimental grants it, beneficial/neutral do not). */
    boolean exitRightFor(MigrationPlan plan, String deltaClass) {
        Map<String, Object> pack = json.readMap(plan.getJurisdictionJson());
        if (pack.get("exitRightByDeltaClass") instanceof Map<?, ?> byClass
                && byClass.get(deltaClass) != null) {
            return Boolean.parseBoolean(String.valueOf(byClass.get(deltaClass)));
        }
        return "detrimental".equals(deltaClass);
    }

    /** Segment excludes match the party's region (or its id, for explicit
     *  carve-outs). Richer segment resolution is the CDP's job, not ours. */
    private boolean excludedBySegment(String partyId, List<String> segmentExcludes) {
        if (segmentExcludes.contains(partyId)) {
            return true;
        }
        return parties.getIndividual(partyId)
                .map(p -> p.get("region") != null && segmentExcludes.contains(String.valueOf(p.get("region"))))
                .orElse(false);
    }

    private static String customerPartyIn(Map<String, Object> product) {
        if (!(product.get("relatedParty") instanceof List<?> related)) {
            return null;
        }
        for (Object rp : related) {
            if (rp instanceof Map<?, ?> m && "customer".equalsIgnoreCase(String.valueOf(m.get("role")))
                    && m.get("id") != null) {
                return String.valueOf(m.get("id"));
            }
        }
        return null;
    }

    private static String nameOf(Object offeringRef) {
        return offeringRef instanceof Map<?, ?> m && m.get("name") != null
                ? String.valueOf(m.get("name")) : null;
    }

    /** What rollback needs: the product as it stood before we touched it. */
    private static Map<String, Object> snapshotOf(Map<String, Object> product) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : List.of("id", "name", "status", "productOffering",
                "productCharacteristic", "relatedParty")) {
            if (product.get(key) != null) {
                snapshot.put(key, product.get(key));
            }
        }
        return snapshot;
    }

    private static List<String> listOf(Object raw) {
        return raw instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
