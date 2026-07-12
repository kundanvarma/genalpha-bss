package com.bss.billing.service;

import com.bss.billing.api.ApiConstants;
import com.bss.billing.client.DownstreamClients;
import com.bss.billing.entity.AppliedBillingRate;
import com.bss.billing.entity.CustomerBill;
import com.bss.billing.events.DomainEventPublisher;
import com.bss.billing.exception.BadRequestException;
import com.bss.billing.repository.AppliedBillingRateRepository;
import com.bss.billing.repository.CustomerBillRepository;
import com.bss.billing.security.PartyScope;
import com.bss.billing.security.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The billing run: rate every customer's active inventory products against
 * the catalog's recurring prices and cut one bill per customer for the
 * period (idempotent — a customer already billed for the period is skipped).
 * v1 rates against the catalog's current prices; a production rater would
 * freeze prices at sale time.
 */
@Service
public class BillingRunService {

    private final CustomerBillRepository bills;
    private final AppliedBillingRateRepository rates;
    private final DownstreamClients.InventoryClient inventory;
    private final DownstreamClients.CatalogClient catalog;
    private final DownstreamClients.UsageClient usage;
    private final DownstreamClients.PromotionClient promotions;
    private final DownstreamClients.PricingClient pricing;
    private final DownstreamClients.OrgClient orgs;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;

    public BillingRunService(CustomerBillRepository bills, AppliedBillingRateRepository rates,
            DownstreamClients.InventoryClient inventory, DownstreamClients.CatalogClient catalog,
            DownstreamClients.UsageClient usage, DownstreamClients.PromotionClient promotions,
            DownstreamClients.PricingClient pricing, DownstreamClients.OrgClient orgs,
            DomainEventPublisher events, PartyScope partyScope,
            TenantScope tenantScope) {
        this.bills = bills;
        this.rates = rates;
        this.inventory = inventory;
        this.catalog = catalog;
        this.usage = usage;
        this.promotions = promotions;
        this.pricing = pricing;
        this.orgs = orgs;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> run() {
        if (partyScope.scopedPartyId().isPresent()) {
            throw new BadRequestException("billing runs are a back-office operation");
        }
        // The run is triggered by an authenticated staff request, so the
        // caller's tenant scopes everything the run reads and creates.
        String tenantId = tenantScope.currentTenantId();
        LocalDate periodStart = LocalDate.now().withDayOfMonth(1);
        LocalDate periodEnd = periodStart.plusMonths(1).minusDays(1);

        // Owner -> their active products, owner derived from the customer related party.
        Map<String, List<Map<String, Object>>> byOwner = new LinkedHashMap<>();
        for (Map<String, Object> product : inventory.activeProducts()) {
            String owner = ((List<Map<String, Object>>) product.getOrDefault("relatedParty", List.of())).stream()
                    .filter(p -> "customer".equalsIgnoreCase(String.valueOf(p.get("role"))))
                    .map(p -> String.valueOf(p.get("id")))
                    .findFirst().orElse(null);
            if (owner != null) {
                byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(product);
            }
        }

        // B2B consolidation: an organization's members bill together — one
        // invoice per company, with per-person attribution on the lines. A
        // person with no organization (or an unreachable party service) keeps
        // their own bill, exactly as before.
        Map<String, List<Map<String, Object>>> byAccount = new LinkedHashMap<>();
        Map<String, List<String>> membersOf = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byOwner.entrySet()) {
            java.util.Optional<String> org = orgs == null ? java.util.Optional.empty()
                    : orgs.organizationOf(e.getKey());
            String account = (org == null ? java.util.Optional.<String>empty() : org).orElse(e.getKey());
            byAccount.computeIfAbsent(account, k -> new ArrayList<>()).addAll(e.getValue());
            membersOf.computeIfAbsent(account, k -> new ArrayList<>()).add(e.getKey());
        }

        Map<String, BigDecimal> priceCache = new HashMap<>();
        Map<String, String> unitCache = new HashMap<>();
        int created = 0;
        int skipped = 0;
        for (Map.Entry<String, List<Map<String, Object>>> owner : byAccount.entrySet()) {
            if (bills.existsByTenantIdAndOwnerPartyIdAndPeriodStart(tenantId, owner.getKey(), periodStart)) {
                skipped++;
                continue;
            }
            List<AppliedBillingRate> billRates = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            String unit = "EUR";
            Map<String, BigDecimal> monthlyByOffering = new HashMap<>();
            for (Map<String, Object> product : owner.getValue()) {
                Object offeringRef = product.get("productOffering");
                if (!(offeringRef instanceof Map<?, ?> ref) || ref.get("id") == null) {
                    continue;
                }
                String offeringId = String.valueOf(ref.get("id"));
                BigDecimal monthly = priceCache.computeIfAbsent(offeringId, id -> monthlyFor(id, unitCache));
                if (monthly.signum() <= 0) {
                    continue;
                }
                unit = unitCache.getOrDefault(offeringId, unit);
                AppliedBillingRate rate = new AppliedBillingRate();
                rate.setId(UUID.randomUUID().toString());
                rate.setTenantId(tenantId);
                rate.setName(String.valueOf(product.getOrDefault("name", offeringId)));
                rate.setRateType("recurringCharge");
                rate.setAmountValue(monthly);
                rate.setAmountUnit(unit);
                rate.setProductJson("{\"id\":\"" + product.get("id") + "\"}");
                rate.setOwnerPartyId(((List<Map<String, Object>>) product.getOrDefault("relatedParty", List.of())).stream()
                        .filter(p -> "customer".equalsIgnoreCase(String.valueOf(p.get("role"))))
                        .map(p -> String.valueOf(p.get("id"))).findFirst().orElse(owner.getKey()));
                rate.setRateDate(OffsetDateTime.now());
                billRates.add(rate);
                total = total.add(monthly);
                monthlyByOffering.merge(offeringId, monthly, BigDecimal::add);
            }
            // Metered charges: rate this party's usage for the period and put
            // the overage on the same bill as the recurring charges.
            for (String member : membersOf.get(owner.getKey()))
            for (Map<String, Object> usageCharge : usage.rateForParty(
                    member, periodStart.toString(), periodEnd.toString())) {
                Map<String, Object> amount = (Map<String, Object>) usageCharge.get("amount");
                AppliedBillingRate rate = new AppliedBillingRate();
                rate.setId(UUID.randomUUID().toString());
                rate.setTenantId(tenantId);
                rate.setName(String.valueOf(usageCharge.get("name")));
                rate.setRateType("usageCharge");
                rate.setAmountValue(new BigDecimal(String.valueOf(amount.get("value"))));
                rate.setAmountUnit(String.valueOf(amount.getOrDefault("unit", unit)));
                rate.setOwnerPartyId(owner.getKey());
                rate.setRateDate(OffsetDateTime.now());
                billRates.add(rate);
                total = total.add(rate.getAmountValue());
            }
            // Earned discounts: each redemption takes its percentage off the
            // recurring charges of the offerings it applies to, while its
            // duration window is open.
            for (String member : membersOf.get(owner.getKey()))
            for (Map<String, Object> redemption : promotions.redemptionsFor(member)) {
                BigDecimal discount = discountFor(redemption, monthlyByOffering, periodStart);
                if (discount.signum() <= 0) {
                    continue;
                }
                AppliedBillingRate rate = new AppliedBillingRate();
                rate.setId(UUID.randomUUID().toString());
                rate.setTenantId(tenantId);
                rate.setName("Promo " + redemption.get("code") + ": " + redemption.get("name")
                        + " (-" + new BigDecimal(String.valueOf(redemption.get("percentage"))).stripTrailingZeros().toPlainString() + "%)");
                rate.setRateType("discount");
                rate.setAmountValue(discount.negate());
                rate.setAmountUnit(unit);
                rate.setOwnerPartyId(owner.getKey());
                rate.setRateDate(OffsetDateTime.now());
                billRates.add(rate);
                total = total.subtract(discount);
            }
            // Data-authored pricing rules: segment/eligibility-driven adjustments
            // applied to the recurring base, authored in the console with no
            // redeploy. Fails open — an empty result leaves the base price.
            BigDecimal recurringBase = monthlyByOffering.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (recurringBase.signum() > 0) {
                Map<String, Object> pricingContext = new HashMap<>();
                pricingContext.put("subtotal", recurringBase);
                pricingContext.put("party", owner.getKey());
                pricingContext.put("offeringIds", new ArrayList<>(monthlyByOffering.keySet()));
                List<Map<String, Object>> priceAdjustments = pricing.adjustments(pricingContext);
                for (Map<String, Object> adjustment : priceAdjustments == null ? List.<Map<String, Object>>of() : priceAdjustments) {
                    BigDecimal amount = new BigDecimal(String.valueOf(adjustment.getOrDefault("amount", "0")));
                    if (amount.signum() == 0) {
                        continue;
                    }
                    AppliedBillingRate rate = new AppliedBillingRate();
                    rate.setId(UUID.randomUUID().toString());
                    rate.setTenantId(tenantId);
                    rate.setName(String.valueOf(adjustment.getOrDefault("label", "Price adjustment")));
                    rate.setRateType("priceAdjustment");
                    rate.setAmountValue(amount);
                    rate.setAmountUnit(unit);
                    rate.setOwnerPartyId(owner.getKey());
                    rate.setRateDate(OffsetDateTime.now());
                    billRates.add(rate);
                    total = total.add(amount);
                }
            }
            if (billRates.isEmpty()) {
                continue;
            }
            CustomerBill bill = new CustomerBill();
            String id = UUID.randomUUID().toString();
            bill.setId(id);
            bill.setTenantId(tenantId);
            bill.setHref(ApiConstants.BASE_PATH + "/customerBill/" + id);
            bill.setBillNo("BILL-" + periodStart.format(DateTimeFormatter.ofPattern("yyyyMM"))
                    + "-" + id.substring(0, 8).toUpperCase());
            bill.setState(CustomerBill.NEW);
            bill.setAmountDueValue(total);
            bill.setAmountDueUnit(unit);
            bill.setPeriodStart(periodStart);
            bill.setPeriodEnd(periodEnd);
            bill.setOwnerPartyId(owner.getKey());
            bill.setBillDate(OffsetDateTime.now());
            bill.setLastUpdate(OffsetDateTime.now());
            bills.save(bill);
            billRates.forEach(r -> r.setBillId(id));
            rates.saveAll(billRates);
            events.publish("CustomerBillCreateEvent", "customerBill", Map.of(
                    "id", id,
                    "billNo", bill.getBillNo(),
                    "amountDue", Map.of("unit", unit, "value", total),
                    "relatedParty", List.of(Map.of(
                            "id", owner.getKey(), "role", "customer", "@referredType", "Individual"))));
            created++;
        }
        return Map.of("billsCreated", created, "customersSkipped", skipped,
                "billingPeriod", Map.of("startDateTime", periodStart.toString(), "endDateTime", periodEnd.toString()));
    }

    /** A redemption's value against this bill's recurring base, 0 if expired or nothing matches. */
    @SuppressWarnings("unchecked")
    private BigDecimal discountFor(Map<String, Object> redemption,
            Map<String, BigDecimal> monthlyByOffering, LocalDate periodStart) {
        if (redemption.get("monthsLeft") instanceof Number monthsLeft) {
            OffsetDateTime redeemedAt = redemption.get("createdAt") == null ? null
                    : OffsetDateTime.parse(String.valueOf(redemption.get("createdAt")));
            if (redeemedAt != null && java.time.temporal.ChronoUnit.MONTHS.between(
                    redeemedAt.toLocalDate().withDayOfMonth(1), periodStart) >= monthsLeft.longValue()) {
                return BigDecimal.ZERO;
            }
        }
        List<String> appliesTo = redemption.get("appliesTo") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        BigDecimal base = monthlyByOffering.entrySet().stream()
                .filter(e -> appliesTo.isEmpty() || appliesTo.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal percentage = new BigDecimal(String.valueOf(redemption.get("percentage")));
        return base.multiply(percentage).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    /** Monthly recurring total of an offering's linked prices, per catalog right now. */
    @SuppressWarnings("unchecked")
    private BigDecimal monthlyFor(String offeringId, Map<String, String> unitCache) {
        Map<String, Object> offering = catalog.offering(offeringId);
        if (offering == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> priceRef
                : (List<Map<String, Object>>) offering.getOrDefault("productOfferingPrice", List.of())) {
            Map<String, Object> price = catalog.price(String.valueOf(priceRef.get("id")));
            if (price == null || !"recurring".equals(price.get("priceType"))
                    || !(price.get("price") instanceof Map<?, ?> money) || money.get("value") == null) {
                continue;
            }
            total = total.add(new BigDecimal(String.valueOf(money.get("value"))));
            if (money.get("unit") != null) {
                unitCache.put(offeringId, String.valueOf(money.get("unit")));
            }
        }
        return total;
    }
}
