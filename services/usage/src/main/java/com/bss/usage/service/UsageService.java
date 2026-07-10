package com.bss.usage.service;

import com.bss.usage.api.ApiConstants;
import com.bss.usage.entity.RatedCharge;
import com.bss.usage.entity.UsageAllowance;
import com.bss.usage.entity.UsageRecord;
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
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public UsageService(UsageRecordRepository records, UsageAllowanceRepository allowances,
            RatedChargeRepository ratedCharges, DomainEventPublisher events, PartyScope partyScope,
            TenantScope tenantScope, ObjectMapper objectMapper) {
        this.records = records;
        this.allowances = allowances;
        this.ratedCharges = ratedCharges;
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
        Map<String, Object> characteristic = dto.get("usageCharacteristic") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : null;
        if (owner == null || dto.get("usageType") == null || characteristic == null
                || characteristic.get("value") == null) {
            throw new BadRequestException(
                    "usageType, usageCharacteristic.value and relatedParty (role customer) are required");
        }
        UsageRecord entity = new UsageRecord();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/usage/" + id);
        entity.setUsageSpecName(String.valueOf(dto.get("usageType")));
        entity.setUsageDate(dto.get("usageDate") == null ? OffsetDateTime.now()
                : OffsetDateTime.parse(String.valueOf(dto.get("usageDate"))));
        entity.setValue(new BigDecimal(String.valueOf(characteristic.get("value"))));
        entity.setUnits(characteristic.get("units") == null ? "unit"
                : String.valueOf(characteristic.get("units")));
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
                BigDecimal over = total.subtract(rule.getAllowanceValue());
                if (over.signum() > 0) {
                    RatedCharge charge = new RatedCharge();
                    charge.setId(UUID.randomUUID().toString());
                    charge.setTenantId(tenantId);
                    charge.setOwnerPartyId(ownerPartyId);
                    charge.setName(first.getUsageSpecName() + " overage: " + over.stripTrailingZeros().toPlainString()
                            + " " + rule.getUnits() + " over " + rule.getAllowanceValue().stripTrailingZeros().toPlainString()
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

        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (UsageRecord r : monthly) {
            String key = r.getProductOfferingId() + "|" + r.getUsageSpecName();
            Map<String, Object> bucket = buckets.computeIfAbsent(key, k -> {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("name", r.getUsageSpecName());
                b.put("usedValue", BigDecimal.ZERO);
                b.put("units", r.getUnits());
                List<UsageAllowance> rules = r.getProductOfferingId() == null ? List.of()
                        : allowances.findByTenantIdAndProductOfferingIdAndUsageSpecName(
                                tenantId, r.getProductOfferingId(), r.getUsageSpecName());
                if (!rules.isEmpty()) {
                    b.put("allowedValue", rules.get(0).getAllowanceValue());
                }
                return b;
            });
            bucket.put("usedValue", ((BigDecimal) bucket.get("usedValue")).add(r.getValue()));
        }
        return Map.of(
                "id", UUID.randomUUID().toString(),
                "@type", "UsageConsumptionReport",
                "relatedParty", List.of(Map.of("id", party, "role", "customer")),
                "period", Map.of("startDateTime", periodStart.toString()),
                "bucket", List.copyOf(buckets.values()));
    }

    private Map<String, Object> toRecordMap(UsageRecord r) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("href", r.getHref());
        map.put("usageType", r.getUsageSpecName());
        map.put("usageDate", r.getUsageDate());
        map.put("usageCharacteristic", Map.of("value", r.getValue(), "units", r.getUnits()));
        map.put("relatedParty", List.of(Map.of("id", r.getOwnerPartyId(), "role", "customer")));
        map.put("status", r.getStatus());
        map.put("@type", "Usage");
        return map;
    }

    private Map<String, Object> chargeMap(RatedCharge c) {
        return Map.of(
                "ownerPartyId", c.getOwnerPartyId(),
                "name", c.getName(),
                "amount", Map.of("unit", c.getAmountUnit(), "value", c.getAmountValue()));
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
