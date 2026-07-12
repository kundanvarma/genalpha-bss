package com.bss.usage.service;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.entity.RatedCharge;
import com.bss.usage.entity.UsageAllowance;
import com.bss.usage.entity.UsageRecord;
import com.bss.usage.entity.UsageSpecification;
import com.bss.usage.events.DomainEventPublisher;
import com.bss.usage.exception.BadRequestException;
import com.bss.usage.repository.RatedChargeRepository;
import com.bss.usage.repository.UsageAllowanceRepository;
import com.bss.usage.repository.UsageRecordRepository;
import com.bss.usage.security.PartyScope;
import com.bss.usage.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The BSS side of charging. Ingest is the mediation/OCS seam — production
 * feeds arrive from the network; dev uses a simulator. Rating aggregates a
 * period's records per (owner, offering, spec) against the offering's
 * allowance and turns the excess into charges billing picks up. Real-time
 * credit control belongs to the network's OCS/CHF, deliberately not here.
 */
@Service
public class UsageService {

    public static final String RECEIVED = "received";
    public static final String RATED = "rated";

    private final UsageRecordRepository records;
    private final UsageAllowanceRepository allowances;
    private final RatedChargeRepository ratedCharges;
    private final com.bss.usage.repository.AllowanceBoostRepository boosts;
    private final com.bss.usage.repository.UsageSpecificationRepository specs;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public UsageService(UsageRecordRepository records, UsageAllowanceRepository allowances,
            com.bss.usage.repository.UsageSpecificationRepository specs,
            RatedChargeRepository ratedCharges,
            com.bss.usage.repository.AllowanceBoostRepository boosts,
            DomainEventPublisher events, PartyScope partyScope,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.records = records;
        this.allowances = allowances;
        this.specs = specs;
        this.ratedCharges = ratedCharges;
        this.boosts = boosts;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    /** Mediation seam: one usage record in, verbatim semantics, no rating yet. */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> ingest(Map<String, Object> dto) {
        String owner = null;
        if (dto.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && "customer".equalsIgnoreCase(String.valueOf(ref.get("role")))) {
                    owner = String.valueOf(ref.get("id"));
                }
            }
        }
        // TMF635: only usageSpecification is meaningful on intake; usageType,
        // usageCharacteristic and relatedParty are optional per the spec. Store
        // the posted body so it round-trips; derive the app fields when present.
        Map<String, Object> characteristic = dto.get("usageCharacteristic") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : (dto.get("usageCharacteristic") instanceof List<?> cl && !cl.isEmpty()
                        && cl.get(0) instanceof Map<?, ?> cm ? (Map<String, Object>) cm : null);
        UsageRecord entity = new UsageRecord();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/usage/" + id);
        entity.setPayloadJson(writeJson(dto));
        entity.setUsageSpecName(dto.get("usageType") == null ? null : String.valueOf(dto.get("usageType")));
        entity.setUsageDate(dto.get("usageDate") == null ? OffsetDateTime.now()
                : OffsetDateTime.parse(String.valueOf(dto.get("usageDate"))));
        if (characteristic != null && characteristic.get("value") != null) {
            entity.setValue(new BigDecimal(String.valueOf(characteristic.get("value"))));
            entity.setUnits(characteristic.get("units") == null ? "unit" : String.valueOf(characteristic.get("units")));
        } else {
            entity.setValue(BigDecimal.ZERO);
            entity.setUnits("unit");
        }
        entity.setOwnerPartyId(owner);
        if (dto.get("productOffering") instanceof Map<?, ?> off && off.get("id") != null) {
            entity.setProductOfferingId(String.valueOf(off.get("id")));
        }
        entity.setStatus(RECEIVED);
        entity.setCreatedAt(OffsetDateTime.now());
        records.save(entity);
        return toRecordMap(entity);
    }

    /**
     * Rate one party's unrated records inside a period: per (offering, spec),
     * usage beyond the offering's allowance becomes an overage charge. Rated
     * records never rate twice. Returns this period's charges for the party.
     */
    @Transactional
    public List<Map<String, Object>> rateForParty(String ownerPartyId, LocalDate periodStart, LocalDate periodEnd) {
        String tenantId = tenantScope.currentTenantId();
        OffsetDateTime from = periodStart.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = periodEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<UsageRecord> unrated = records.findByTenantIdAndOwnerPartyIdAndStatusAndUsageDateBetween(
                tenantId, ownerPartyId, RECEIVED, from, to);

        // Purchased top-ups raise the allowance before overage is charged.
        Map<String, BigDecimal> boostBySpec = boostTotals(tenantId, ownerPartyId, periodStart);
        // (offeringId, spec) -> summed usage
        Map<String, List<UsageRecord>> groups = new LinkedHashMap<>();
        for (UsageRecord r : unrated) {
            groups.computeIfAbsent(r.getProductOfferingId() + "|" + r.getUsageSpecName(),
                    k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<UsageRecord>> group : groups.entrySet()) {
            List<UsageRecord> rs = group.getValue();
            UsageRecord first = rs.get(0);
            BigDecimal total = rs.stream().map(UsageRecord::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<UsageAllowance> rules = first.getProductOfferingId() == null ? List.of()
                    : allowances.findByTenantIdAndProductOfferingIdAndUsageSpecName(
                            tenantId, first.getProductOfferingId(), first.getUsageSpecName());
            if (!rules.isEmpty()) {
                UsageAllowance rule = rules.get(0);
                BigDecimal included = rule.getAllowanceValue()
                        .add(boostBySpec.getOrDefault(first.getUsageSpecName(), BigDecimal.ZERO));
                BigDecimal over = total.subtract(included);
                if (over.signum() > 0) {
                    RatedCharge charge = new RatedCharge();
                    charge.setId(UUID.randomUUID().toString());
                    charge.setTenantId(tenantId);
                    charge.setOwnerPartyId(ownerPartyId);
                    charge.setName(first.getUsageSpecName() + " overage: " + over.stripTrailingZeros().toPlainString()
                            + " " + rule.getUnits() + " over " + included.stripTrailingZeros().toPlainString()
                            + " " + rule.getUnits() + " included");
                    charge.setAmountValue(over.multiply(rule.getOveragePriceValue())
                            .setScale(2, RoundingMode.HALF_UP));
                    charge.setAmountUnit(rule.getOveragePriceUnit());
                    charge.setPeriodStart(periodStart);
                    charge.setCreatedAt(OffsetDateTime.now());
                    ratedCharges.save(charge);
                    events.publish("UsageRatedEvent", "ratedCharge", chargeMap(charge));
                }
            }
            rs.forEach(r -> r.setStatus(RATED));
            records.saveAll(rs);
        }
        return ratedCharges.findByTenantIdAndOwnerPartyIdAndPeriodStart(tenantId, ownerPartyId, periodStart)
                .stream().map(this::chargeMap).toList();
    }

    /** TMF677: this month's buckets for the calling customer (or a named party for staff). */
    @Transactional(readOnly = true)
    public Map<String, Object> consumptionReport(String requestedPartyId) {
        String tenantId = tenantScope.currentTenantId();
        String party = partyScope.scopedPartyId().orElse(requestedPartyId);
        if (party == null) {
            throw new BadRequestException("relatedPartyId is required for unscoped callers");
        }
        LocalDate periodStart = LocalDate.now().withDayOfMonth(1);
        OffsetDateTime from = periodStart.atStartOfDay().atOffset(ZoneOffset.UTC);
        List<UsageRecord> monthly = records.findByTenantIdAndOwnerPartyIdAndUsageDateBetween(
                tenantId, party, from, OffsetDateTime.now());

        // Purchased top-ups extend this period's allowance per usage spec.
        Map<String, BigDecimal> boostBySpec = boostTotals(tenantId, party, periodStart);
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (UsageRecord r : monthly) {
            String key = r.getProductOfferingId() + "|" + r.getUsageSpecName();
            Map<String, Object> bucket = buckets.computeIfAbsent(key, k -> {
                Map<String, Object> b = new LinkedHashMap<>();
                // TMF677: buckets are addressable — a stable id per (party, offering, spec).
                b.put("id", "bkt-" + Integer.toHexString((party + "|" + k).hashCode()));
                b.put("name", r.getUsageSpecName());
                b.put("usedValue", BigDecimal.ZERO);
                b.put("units", r.getUnits());
                List<UsageAllowance> rules = r.getProductOfferingId() == null ? List.of()
                        : allowances.findByTenantIdAndProductOfferingIdAndUsageSpecName(
                                tenantId, r.getProductOfferingId(), r.getUsageSpecName());
                BigDecimal extra = boostBySpec.get(r.getUsageSpecName());
                if (!rules.isEmpty() || extra != null) {
                    BigDecimal base = rules.isEmpty() ? BigDecimal.ZERO : rules.get(0).getAllowanceValue();
                    b.put("allowedValue", extra == null ? base : base.add(extra));
                }
                return b;
            });
            bucket.put("usedValue", ((BigDecimal) bucket.get("usedValue")).add(r.getValue()));
        }
        // A deterministic report per party: the same customer always gets the
        // same report id, so the report is addressable as a resource (TMF677).
        String reportId = "ucr-" + party;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("id", reportId);
        report.put("href", ApiConstants.CONSUMPTION_BASE_PATH + "/usageConsumptionReport/" + reportId);
        report.put("name", "usageConsumptionReport-" + party);
        report.put("effectiveDate", OffsetDateTime.now().toString());
        report.put("@type", "UsageConsumptionReport");
        report.put("relatedParty", List.of(Map.of("id", party, "role", "customer")));
        report.put("period", Map.of("startDateTime", periodStart.toString()));
        report.put("bucket", List.copyOf(buckets.values()));
        return report;
    }

    // ---- TMF677 usageConsumptionReport as a resource ----

    /** One report per party with usage this month, so the collection is listable. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findReports(Map<String, String> filters) {
        String tenantId = tenantScope.currentTenantId();
        // A customer only ever sees their own report; staff see the tenant's.
        List<String> parties = partyScope.scopedPartyId().map(List::of)
                .orElseGet(() -> records.findByTenantId(tenantId).stream()
                        .map(UsageRecord::getOwnerPartyId)
                        .filter(java.util.Objects::nonNull)
                        .distinct().toList());
        return parties.stream().map(this::consumptionReport)
                .filter(r -> !((List<?>) r.get("bucket")).isEmpty())
                .filter(r -> filters.get("id") == null || filters.get("id").equals(r.get("id")))
                .filter(r -> filters.get("name") == null || filters.get("name").equals(r.get("name")))
                .filter(r -> filters.get("bucket.id") == null || ((List<?>) r.get("bucket")).stream()
                        .anyMatch(b -> b instanceof Map<?, ?> bm
                                && filters.get("bucket.id").equals(bm.get("id"))))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findReportById(String id) {
        if (!id.startsWith("ucr-")) {
            throw new com.bss.usage.exception.NotFoundException("UsageConsumptionReport '" + id + "' not found");
        }
        String party = id.substring("ucr-".length());
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(party)) {
                throw new com.bss.usage.exception.NotFoundException("UsageConsumptionReport '" + id + "' not found");
            }
        });
        Map<String, Object> report = consumptionReport(party);
        if (((List<?>) report.get("bucket")).isEmpty()) {
            throw new com.bss.usage.exception.NotFoundException("UsageConsumptionReport '" + id + "' not found");
        }
        return report;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toRecordMap(UsageRecord r) {
        Map<String, Object> map = new LinkedHashMap<>();
        Object stored = readJsonValue(r.getPayloadJson());
        if (stored instanceof Map<?, ?> m) {
            map.putAll((Map<String, Object>) m);
        }
        map.put("id", r.getId());
        map.put("href", r.getHref());
        if (r.getUsageSpecName() != null) {
            map.put("usageType", r.getUsageSpecName());
        }
        map.put("usageDate", r.getUsageDate());
        if (map.get("usageCharacteristic") == null) {
            map.put("usageCharacteristic", Map.of("value", r.getValue(), "units", r.getUnits()));
        }
        if (r.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", r.getOwnerPartyId(), "role", "customer")));
        }
        map.put("status", r.getStatus());
        // TMF635 mandatory attribute — always present.
        map.putIfAbsent("usageSpecification", Map.of());
        map.put("@type", "Usage");
        return map;
    }

    // ---- Usage as a retrievable resource ----

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findUsage(Map<String, String> filters) {
        return records.findByTenantId(tenantScope.currentTenantId()).stream()
                .filter(r -> filters.get("id") == null || filters.get("id").equals(r.getId()))
                .filter(r -> filters.get("href") == null || filters.get("href").equals(r.getHref()))
                .map(this::toRecordMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findUsageById(String id) {
        return toRecordMap(records.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> new com.bss.usage.exception.NotFoundException("Usage '" + id + "' not found")));
    }

    // ---- UsageSpecification resource (TMF635) ----

    @Transactional
    public Map<String, Object> createSpec(Map<String, Object> dto) {
        UsageSpecification e = new UsageSpecification();
        String id = UUID.randomUUID().toString();
        e.setId(id);
        e.setTenantId(tenantScope.currentTenantId());
        e.setHref(ApiConstants.BASE_PATH + "/usageSpecification/" + id);
        e.setName(dto.get("name") == null ? null : String.valueOf(dto.get("name")));
        e.setUnits(dto.get("units") == null ? "unit" : String.valueOf(dto.get("units")));
        e.setPayloadJson(writeJson(dto));
        e.setLastUpdate(OffsetDateTime.now());
        return toSpecMap(specs.save(e));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findSpecs(Map<String, String> filters) {
        return specs.findByTenantId(tenantScope.currentTenantId()).stream()
                .filter(s -> filters.get("id") == null || filters.get("id").equals(s.getId()))
                .filter(s -> filters.get("href") == null || filters.get("href").equals(s.getHref()))
                .filter(s -> filters.get("name") == null || filters.get("name").equals(s.getName()))
                .map(this::toSpecMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findSpecById(String id) {
        return toSpecMap(specs.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> new com.bss.usage.exception.NotFoundException("UsageSpecification '" + id + "' not found")));
    }

    @Transactional
    public Map<String, Object> patchSpec(String id, Map<String, Object> dto) {
        UsageSpecification e = specs.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> new com.bss.usage.exception.NotFoundException("UsageSpecification '" + id + "' not found"));
        if (dto.containsKey("name")) {
            e.setName(String.valueOf(dto.get("name")));
        }
        Object stored = readJsonValue(e.getPayloadJson());
        Map<String, Object> merged = new LinkedHashMap<>();
        if (stored instanceof Map<?, ?> m) {
            merged.putAll(castMap(m));
        }
        merged.putAll(dto);
        e.setPayloadJson(writeJson(merged));
        e.setLastUpdate(OffsetDateTime.now());
        return toSpecMap(specs.save(e));
    }

    @Transactional
    public void deleteSpec(String id) {
        UsageSpecification e = specs.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> new com.bss.usage.exception.NotFoundException("UsageSpecification '" + id + "' not found"));
        specs.delete(e);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toSpecMap(UsageSpecification s) {
        Map<String, Object> map = new LinkedHashMap<>();
        Object stored = readJsonValue(s.getPayloadJson());
        if (stored instanceof Map<?, ?> m) {
            map.putAll((Map<String, Object>) m);
        }
        map.put("id", s.getId());
        map.put("href", s.getHref());
        map.put("name", s.getName());
        map.put("lastUpdate", s.getLastUpdate());
        map.put("@type", "UsageSpecification");
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private Object readJsonValue(String s) {
        if (s == null || s.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(s, Object.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> chargeMap(RatedCharge c) {
        return Map.of(
                "ownerPartyId", c.getOwnerPartyId(),
                "name", c.getName(),
                "amount", Map.of("unit", c.getAmountUnit(), "value", c.getAmountValue()));
    }

    /**
     * A completed order carrying a boost-flagged offering (a data top-up) adds
     * allowance to the buyer's CURRENT period. Idempotent per (order, spec) —
     * event delivery is at-least-once. Non-top-up orders fall through untouched.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public void recordTopUps(Map<String, Object> productOrder) {
        if (!"completed".equals(String.valueOf(productOrder.get("state")))) {
            return;
        }
        String tenantId = tenantScope.currentTenantId();
        String orderId = String.valueOf(productOrder.get("id"));
        String owner = productOrder.get("relatedParty") instanceof List<?> parties ? parties.stream()
                .filter(p -> p instanceof Map<?, ?> m && "customer".equalsIgnoreCase(String.valueOf(m.get("role"))))
                .map(p -> String.valueOf(((Map<String, Object>) p).get("id")))
                .findFirst().orElse(null) : null;
        if (owner == null) {
            return;
        }
        Map<String, com.bss.usage.entity.AllowanceBoost> bySpec = new LinkedHashMap<>();
        collectTopUpItems((List<Map<String, Object>>) productOrder.get("productOrderItem"),
                tenantId, owner, orderId, bySpec);
        for (com.bss.usage.entity.AllowanceBoost boost : bySpec.values()) {
            if (boosts.existsByTenantIdAndProductOrderIdAndUsageSpecName(
                    tenantId, orderId, boost.getUsageSpecName())) {
                continue;
            }
            boosts.save(boost);
            events.publish("BucketBalanceChangeEvent", "bucket", Map.of(
                    "relatedParty", List.of(Map.of("id", owner, "role", "customer")),
                    "name", boost.getUsageSpecName(),
                    "boost", boost.getBoostValue(),
                    "units", boost.getUnits() == null ? "unit" : boost.getUnits()));
        }
    }

    @SuppressWarnings("unchecked")
    private void collectTopUpItems(List<Map<String, Object>> items, String tenantId,
            String owner, String orderId, Map<String, com.bss.usage.entity.AllowanceBoost> bySpec) {
        if (items == null) {
            return;
        }
        for (Map<String, Object> item : items) {
            if (item.get("productOffering") instanceof Map<?, ?> off && off.get("id") != null
                    && !"modify".equalsIgnoreCase(String.valueOf(item.get("action")))) {
                int quantity = item.get("quantity") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                for (UsageAllowance rule : allowances.findByTenantIdAndProductOfferingId(
                        tenantId, String.valueOf(off.get("id")))) {
                    if (!rule.isBoost()) {
                        continue;
                    }
                    com.bss.usage.entity.AllowanceBoost boost = bySpec.computeIfAbsent(
                            rule.getUsageSpecName(), spec -> {
                                com.bss.usage.entity.AllowanceBoost b = new com.bss.usage.entity.AllowanceBoost();
                                b.setId(UUID.randomUUID().toString());
                                b.setTenantId(tenantId);
                                b.setOwnerPartyId(owner);
                                b.setUsageSpecName(spec);
                                b.setBoostValue(BigDecimal.ZERO);
                                b.setUnits(rule.getUnits());
                                b.setPeriodStart(LocalDate.now().withDayOfMonth(1));
                                b.setProductOrderId(orderId);
                                b.setCreatedAt(OffsetDateTime.now());
                                return b;
                            });
                    boost.setBoostValue(boost.getBoostValue()
                            .add(rule.getAllowanceValue().multiply(BigDecimal.valueOf(quantity))));
                }
            }
            if (item.get("productOrderItem") instanceof List<?> children) {
                collectTopUpItems((List<Map<String, Object>>) children, tenantId, owner, orderId, bySpec);
            }
        }
    }

    private Map<String, BigDecimal> boostTotals(String tenantId, String party, LocalDate periodStart) {
        Map<String, BigDecimal> bySpec = new LinkedHashMap<>();
        for (com.bss.usage.entity.AllowanceBoost boost
                : boosts.findByTenantIdAndOwnerPartyIdAndPeriodStart(tenantId, party, periodStart)) {
            bySpec.merge(boost.getUsageSpecName(), boost.getBoostValue(), BigDecimal::add);
        }
        return bySpec;
    }

    /** Admin data: which offerings include how much of what, and the price beyond. */
    @Transactional
    public Map<String, Object> createAllowance(Map<String, Object> dto) {
        if (!(dto.get("productOffering") instanceof Map<?, ?> off) || off.get("id") == null
                || dto.get("usageType") == null || dto.get("allowance") == null
                || !(dto.get("overagePrice") instanceof Map<?, ?> price) || price.get("value") == null) {
            throw new BadRequestException(
                    "productOffering.id, usageType, allowance {value, units} and overagePrice {value, unit} are required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> allowance = (Map<String, Object>) dto.get("allowance");
        UsageAllowance entity = new UsageAllowance();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/usageAllowance/" + id);
        entity.setProductOfferingJson(writeJson(dto.get("productOffering")));
        entity.setProductOfferingId(String.valueOf(off.get("id")));
        entity.setUsageSpecName(String.valueOf(dto.get("usageType")));
        entity.setAllowanceValue(new BigDecimal(String.valueOf(allowance.get("value"))));
        entity.setUnits(String.valueOf(allowance.getOrDefault("units", "unit")));
        entity.setOveragePriceValue(new BigDecimal(String.valueOf(price.get("value"))));
        entity.setOveragePriceUnit(String.valueOf(price.get("unit") == null ? "EUR" : price.get("unit")));
        entity.setBoost(Boolean.parseBoolean(String.valueOf(dto.getOrDefault("boost", "false"))));
        entity.setLastUpdate(OffsetDateTime.now());
        allowances.save(entity);
        return allowanceMap(entity);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAllowances() {
        return allowances.findByTenantId(tenantScope.currentTenantId())
                .stream().map(this::allowanceMap).toList();
    }

    private Map<String, Object> allowanceMap(UsageAllowance entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("href", entity.getHref());
        map.put("usageType", entity.getUsageSpecName());
        map.put("productOffering", readJson(entity.getProductOfferingJson()));
        map.put("allowance", Map.of("value", entity.getAllowanceValue(), "units", entity.getUnits()));
        map.put("overagePrice", Map.of("unit", entity.getOveragePriceUnit(), "value", entity.getOveragePriceValue()));
        if (entity.isBoost()) {
            map.put("boost", true);
        }
        map.put("@type", "UsageAllowance");
        return map;
    }

    private Object readJson(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, Object.class);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalStateException("unreadable stored JSON", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unserializable JSON value", e);
        }
    }
}
