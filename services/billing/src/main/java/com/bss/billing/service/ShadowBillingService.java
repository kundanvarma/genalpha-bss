package com.bss.billing.service;

import com.bss.billing.client.DownstreamClients;
import com.bss.billing.entity.AppliedBillingRate;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.entity.ShadowBillDrift;
import com.bss.billing.events.DomainEventPublisher;
import com.bss.billing.repository.AppliedBillingRateRepository;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.repository.ShadowBillDriftRepository;
import com.bss.billing.security.TenantContext;
import com.bss.billing.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P3 — CONTINUOUS SHADOW BILLING: the parallel bill run, standing. Every
 * sweep re-prices a slice of the base against the CURRENT catalog and
 * compares each product's monthly with what the owner's last real bill
 * actually charged for it (the applied-rate receipts). A mismatch means
 * the NEXT invoice will differ from the last — surfaced as a drift row +
 * event BEFORE anyone gets a wrong bill. Nothing here mutates a bill.
 */
@Service
public class ShadowBillingService {

    private static final Logger log = LoggerFactory.getLogger(ShadowBillingService.class);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.05");

    private final BillingRunService runService;
    private final DownstreamClients.InventoryClient inventory;
    private final CustomerBillRepository bills;
    private final AppliedBillingRateRepository rates;
    private final ShadowBillDriftRepository drifts;
    private final TenantRegistry tenants;
    private final DomainEventPublisher events;
    private final int sampleCap;

    public ShadowBillingService(BillingRunService runService,
            DownstreamClients.InventoryClient inventory, CustomerBillRepository bills,
            AppliedBillingRateRepository rates, ShadowBillDriftRepository drifts,
            TenantRegistry tenants, DomainEventPublisher events,
            @Value("${bss.billing.shadow-sample-cap:200}") int sampleCap) {
        this.runService = runService;
        this.inventory = inventory;
        this.bills = bills;
        this.rates = rates;
        this.drifts = drifts;
        this.tenants = tenants;
        this.events = events;
        this.sampleCap = sampleCap;
    }

    @Scheduled(fixedDelayString = "${bss.billing.shadow-tick-ms:300000}")
    public void tick() {
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                sweep(tenant.getId());
            } catch (RuntimeException e) {
                log.warn("shadow billing skipped for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }

    /** Per-tenant rotating cursor: successive ticks cover the WHOLE base, not
     *  the same first slice forever. */
    private final Map<String, Integer> cursor = new java.util.concurrent.ConcurrentHashMap<>();

    /** One tenant's shadow pass; returns the fresh drift rows it raised. */
    @Transactional
    public List<Map<String, Object>> sweep(String tenant) {
        return sweep(tenant, null);
    }

    /** Targeted (partyId) or rotating-window pass. */
    @Transactional
    public List<Map<String, Object>> sweep(String tenant, String partyId) {
        List<Map<String, Object>> products = partyId != null
                ? inventory.productsOf(partyId) : inventory.activeProducts();
        if (partyId == null && products.size() > sampleCap) {
            final int total = products.size();
            int start = cursor.merge(tenant, sampleCap, (a, b) -> (a + b) % Math.max(1, total))
                    - sampleCap;
            if (start < 0) {
                start = 0;
            }
            int end = Math.min(start + sampleCap, products.size());
            log.info("shadow billing: window [{}..{}) of {} active products this pass",
                    start, end, products.size());
            products = products.subList(start, end);
        }
        Map<String, CustomerBill> latestBillOf = new HashMap<>();
        Map<String, List<AppliedBillingRate>> ratesOfBill = new HashMap<>();
        Map<String, String> unitCache = new HashMap<>();
        Map<String, BigDecimal> priceCache = new HashMap<>();
        List<Map<String, Object>> raised = new ArrayList<>();

        for (Map<String, Object> product : products) {
            if (!(product.get("productOffering") instanceof Map<?, ?> ref) || ref.get("id") == null) {
                continue;
            }
            String owner = ownerOf(product);
            if (owner == null) {
                continue;
            }
            CustomerBill last = latestBillOf.computeIfAbsent(owner, o -> bills
                    .findFirstByTenantIdAndOwnerPartyIdOrderByPeriodEndDesc(tenant, o).orElse(null));
            if (last == null) {
                continue;   // never billed — nothing to drift from
            }
            String productId = String.valueOf(product.get("id"));
            List<AppliedBillingRate> billRates = ratesOfBill.computeIfAbsent(last.getId(),
                    b -> rates.findByTenantIdAndBillId(tenant, b));
            AppliedBillingRate billed = billRates.stream()
                    .filter(r -> r.getProductJson() != null && r.getProductJson().contains(productId))
                    .findFirst().orElse(null);
            if (billed == null || billed.getAmountValue() == null) {
                continue;   // this product wasn't a line on the last bill (new since)
            }
            String offeringId = String.valueOf(ref.get("id"));
            BigDecimal current = priceCache.computeIfAbsent(
                    offeringId + "|" + runService.charsOf(product),
                    k -> runService.monthlyFor(offeringId, runService.charsOf(product), unitCache));
            if (current.signum() <= 0) {
                continue;   // de-priced offerings are a catalog decision, not drift
            }
            // RATE vs RATE, never amount vs amount: a mid-cycle joiner's first
            // bill is PRORATED — normalize the billed line back to its monthly
            // equivalent before comparing, or every new customer reads as drift.
            BigDecimal billedMonthly = monthlyEquivalent(billed.getAmountValue(), product, last);
            if (billedMonthly == null) {
                continue;
            }
            BigDecimal delta = current.subtract(billedMonthly);
            if (delta.abs().compareTo(TOLERANCE) <= 0) {
                continue;
            }
            if (drifts.existsByTenantIdAndOwnerPartyIdAndOfferingIdAndBilledMonthlyAndCurrentMonthly(
                    tenant, owner, offeringId, billedMonthly, current)) {
                continue;   // this exact drift is already on the worklist
            }
            ShadowBillDrift row = new ShadowBillDrift();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(tenant);
            row.setOwnerPartyId(owner);
            row.setBillId(last.getId());
            row.setOfferingId(offeringId);
            row.setOfferingName(String.valueOf(ref.get("name")));
            row.setBilledMonthly(billedMonthly);
            row.setCurrentMonthly(current);
            row.setDelta(delta);
            row.setUnit(unitCache.get(offeringId));
            row.setDetectedAt(OffsetDateTime.now());
            drifts.save(row);
            Map<String, Object> payload = toMap(row);
            events.publish("BillDriftDetectedEvent", "shadowBillDrift", payload, tenant);
            raised.add(payload);
            log.info("shadow billing: {} on {} will bill {} next cycle (was {}) — drift {}",
                    owner, row.getOfferingName(), current, billed.getAmountValue(), delta);
        }
        return raised;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String tenant) {
        return drifts.findTop200ByTenantIdOrderByDetectedAtDesc(tenant)
                .stream().map(this::toMap).toList();
    }

    /** The billed line scaled back to a FULL month using the bill period and
     *  the product's start date (proration undone). Null = not comparable. */
    private BigDecimal monthlyEquivalent(BigDecimal billedAmount, Map<String, Object> product,
            CustomerBill bill) {
        java.time.LocalDate pStart = bill.getPeriodStart();
        java.time.LocalDate pEnd = bill.getPeriodEnd();
        if (pStart == null || pEnd == null || pEnd.isBefore(pStart)) {
            return billedAmount;
        }
        java.time.LocalDate from = pStart;
        if (product.get("startDate") != null) {
            try {
                java.time.LocalDate started = OffsetDateTime
                        .parse(String.valueOf(product.get("startDate"))).toLocalDate();
                if (started.isAfter(pEnd)) {
                    return null;
                }
                if (started.isAfter(pStart)) {
                    from = started;
                }
            } catch (Exception ignored) {
                // unparseable start date: treat as full period
            }
        }
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(pStart, pEnd) + 1;
        long billedDays = java.time.temporal.ChronoUnit.DAYS.between(from, pEnd) + 1;
        if (periodDays <= 0 || billedDays <= 0) {
            return null;
        }
        return billedAmount.multiply(BigDecimal.valueOf(periodDays))
                .divide(BigDecimal.valueOf(billedDays), 2, java.math.RoundingMode.HALF_UP);
    }

    private String ownerOf(Map<String, Object> product) {
        for (Object rp : product.get("relatedParty") instanceof List<?> l ? l : List.of()) {
            if (rp instanceof Map<?, ?> m && m.get("id") != null) {
                return String.valueOf(m.get("id"));
            }
        }
        return null;
    }

    private Map<String, Object> toMap(ShadowBillDrift d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("ownerPartyId", d.getOwnerPartyId());
        m.put("billId", d.getBillId());
        m.put("offeringId", d.getOfferingId());
        m.put("offeringName", d.getOfferingName());
        m.put("billedMonthly", d.getBilledMonthly());
        m.put("currentMonthly", d.getCurrentMonthly());
        m.put("delta", d.getDelta());
        m.put("unit", d.getUnit());
        m.put("detectedAt", d.getDetectedAt());
        m.put("@type", "ShadowBillDrift");
        return m;
    }
}
