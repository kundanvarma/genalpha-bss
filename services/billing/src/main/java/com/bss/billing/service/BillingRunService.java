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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(BillingRunService.class);

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

        // Split billing: every product bills to the account of its payer.
        // Orders a company admin places are stamped with a payer related
        // party at ordering time, so company-paid lines land on the company
        // bill while anything a member buys themselves stays on their own.
        // A product with no payer stamp bills to its owner.
        Map<String, List<Map<String, Object>>> byAccount = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> membersOf = new LinkedHashMap<>();
        Map<String, String> primaryAccountOf = new HashMap<>();
        java.util.Set<String> orgAccounts = new java.util.HashSet<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byOwner.entrySet()) {
            String ownerId = e.getKey();
            for (Map<String, Object> product : e.getValue()) {
                String payer = ((List<Map<String, Object>>) product.getOrDefault("relatedParty", List.of())).stream()
                        .filter(p -> "payer".equalsIgnoreCase(String.valueOf(p.get("role"))))
                        .map(p -> String.valueOf(p.get("id")))
                        .findFirst().orElse(ownerId);
                if (!payer.equals(ownerId)) {
                    orgAccounts.add(payer);
                    // usage and earned discounts follow the company-paid plan
                    primaryAccountOf.put(ownerId, payer);
                }
                byAccount.computeIfAbsent(payer, k -> new ArrayList<>()).add(product);
                membersOf.computeIfAbsent(payer, k -> new java.util.LinkedHashSet<>()).add(ownerId);
            }
            primaryAccountOf.putIfAbsent(ownerId, ownerId);
        }

        Map<String, BigDecimal> priceCache = new HashMap<>();
        Map<String, String> unitCache = new HashMap<>();

        // Configurable device co-pay: the company pays a device's monthly
        // charge only up to its deviceAllowance policy; anything above it
        // moves to the employee's personal bill as its own labelled line.
        Map<String, BigDecimal> companyShareOf = new HashMap<>();
        Map<String, List<AppliedBillingRate>> personalExcess = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> categoryCache = new HashMap<>();
        for (String account : orgAccounts) {
            Map<String, Object> allowance = orgs == null ? null
                    : orgs.deviceAllowanceOf(account).orElse(null);
            if (allowance == null || allowance.get("value") == null) {
                continue;
            }
            BigDecimal cap = new BigDecimal(String.valueOf(allowance.get("value")));
            for (Map<String, Object> product : byAccount.get(account)) {
                Object offeringRef = product.get("productOffering");
                if (!(offeringRef instanceof Map<?, ?> ref) || ref.get("id") == null) {
                    continue;
                }
                String offeringId = String.valueOf(ref.get("id"));
                if (!categoriesOf(offeringId, categoryCache).contains("devices")) {
                    continue;
                }
                java.util.TreeMap<String, String> deviceChars = charsOf(product);
                BigDecimal monthly = priceCache.computeIfAbsent(offeringId + "|" + deviceChars,
                        k -> monthlyFor(offeringId, deviceChars, unitCache));
                if (monthly.compareTo(cap) <= 0) {
                    continue;
                }
                String deviceOwner = ((List<Map<String, Object>>) product.getOrDefault("relatedParty", List.of())).stream()
                        .filter(p -> "customer".equalsIgnoreCase(String.valueOf(p.get("role"))))
                        .map(p -> String.valueOf(p.get("id"))).findFirst().orElse(null);
                if (deviceOwner == null) {
                    continue;
                }
                companyShareOf.put(String.valueOf(product.get("id")), cap);
                AppliedBillingRate excess = new AppliedBillingRate();
                excess.setId(UUID.randomUUID().toString());
                excess.setTenantId(tenantId);
                excess.setName(product.getOrDefault("name", offeringId) + " — above company allowance");
                excess.setRateType("recurringCharge");
                excess.setAmountValue(monthly.subtract(cap));
                excess.setAmountUnit(unitCache.getOrDefault(offeringId,
                        String.valueOf(allowance.getOrDefault("unit", "EUR"))));
                excess.setProductJson("{\"id\":\"" + product.get("id") + "\"}");
                excess.setOwnerPartyId(deviceOwner);
                excess.setRateDate(OffsetDateTime.now());
                personalExcess.computeIfAbsent(deviceOwner, k -> new ArrayList<>()).add(excess);
            }
        }
        // An employee whose only personal charge is a device excess still
        // needs a personal bill carrying it.
        for (String deviceOwner : personalExcess.keySet()) {
            byAccount.computeIfAbsent(deviceOwner, k -> new ArrayList<>());
            membersOf.computeIfAbsent(deviceOwner, k -> new java.util.LinkedHashSet<>()).add(deviceOwner);
        }
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
                java.util.TreeMap<String, String> productChars = charsOf(product);
                BigDecimal monthly = priceCache.computeIfAbsent(offeringId + "|" + productChars,
                        k -> monthlyFor(offeringId, productChars, unitCache));
                if (monthly.signum() <= 0) {
                    continue;
                }
                unit = unitCache.getOrDefault(offeringId, unit);
                // Device co-pay: the company bill carries only its share.
                BigDecimal companyShare = companyShareOf.get(String.valueOf(product.get("id")));
                BigDecimal charged = companyShare != null ? companyShare : monthly;
                String rateName = String.valueOf(product.getOrDefault("name", offeringId))
                        + (companyShare != null ? " (company share)" : "");
                AppliedBillingRate rate = new AppliedBillingRate();
                rate.setId(UUID.randomUUID().toString());
                rate.setTenantId(tenantId);
                rate.setName(rateName);
                rate.setRateType("recurringCharge");
                rate.setAmountValue(charged);
                rate.setAmountUnit(unit);
                rate.setProductJson("{\"id\":\"" + product.get("id") + "\"}");
                rate.setOwnerPartyId(((List<Map<String, Object>>) product.getOrDefault("relatedParty", List.of())).stream()
                        .filter(p -> "customer".equalsIgnoreCase(String.valueOf(p.get("role"))))
                        .map(p -> String.valueOf(p.get("id"))).findFirst().orElse(owner.getKey()));
                rate.setRateDate(OffsetDateTime.now());
                billRates.add(rate);
                total = total.add(charged);
                monthlyByOffering.merge(offeringId, charged, BigDecimal::add);
            }
            // The employee side of a device co-pay: excess lands here, on
            // the owner's personal bill — never back on a company bill.
            if (!orgAccounts.contains(owner.getKey())) {
                for (AppliedBillingRate excess : personalExcess.getOrDefault(owner.getKey(), List.of())) {
                    unit = excess.getAmountUnit();
                    billRates.add(excess);
                    total = total.add(excess.getAmountValue());
                }
            }
            // Metered charges: rate each member's usage for the period on
            // their primary account — the one carrying their plan — so a
            // member with both a company bill and a personal extras bill is
            // rated exactly once.
            for (String member : membersOf.get(owner.getKey())) {
            if (!owner.getKey().equals(primaryAccountOf.getOrDefault(member, member))) {
                continue;
            }
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
            }
            // Earned discounts: each redemption takes its percentage off the
            // recurring charges of the offerings it applies to, while its
            // duration window is open — applied on the member's primary
            // account, where those recurring charges live.
            for (String member : membersOf.get(owner.getKey())) {
            if (!owner.getKey().equals(primaryAccountOf.getOrDefault(member, member))) {
                continue;
            }
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
                // colour campaigns: the account's configured picks, as
                // "name:value" strings — a rule conditioned on
                // "color:Icy Blue" fires on the bill exactly as it did in
                // the cart preview
                java.util.Set<String> characteristicValues = new java.util.TreeSet<>();
                for (Map<String, Object> product : owner.getValue()) {
                    charsOf(product).forEach((n, v) -> characteristicValues.add(n + ":" + v));
                }
                pricingContext.put("characteristicValues", new ArrayList<>(characteristicValues));
                // B2B: negotiated per-org deals and volume tiers are pricing
                // rules conditioned on these — authored as data, no redeploy.
                if (orgAccounts.contains(owner.getKey())) {
                    pricingContext.put("organizationId", owner.getKey());
                }
                pricingContext.put("memberCount", membersOf.get(owner.getKey()).size());
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
            java.util.Set<String> units = billRates.stream()
                    .map(AppliedBillingRate::getAmountUnit).collect(java.util.stream.Collectors.toSet());
            if (units.size() > 1) {
                // one operator, one currency: mixed units mean a mis-seeded
                // catalog — bill anyway (fail open) but say so loudly.
                log.warn("bill for account {} mixes currencies {} — check the catalog's price units",
                        owner.getKey(), units);
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
                            "id", owner.getKey(), "role", "customer",
                            "@referredType", orgAccounts.contains(owner.getKey()) ? "Organization" : "Individual"))));
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

    /**
     * An offering's category names, lowercased, cached for the run's lifetime.
     * Fails open to no categories — an unreachable catalog means no co-pay
     * split, never a stopped billing run.
     */
    @SuppressWarnings("unchecked")
    private java.util.Set<String> categoriesOf(String offeringId, Map<String, java.util.Set<String>> cache) {
        return cache.computeIfAbsent(offeringId, id -> {
            try {
                Map<String, Object> offering = catalog.offering(id);
                if (offering == null || !(offering.get("category") instanceof List<?> categories)) {
                    return java.util.Set.of();
                }
                return ((List<Map<String, Object>>) categories).stream()
                        .map(c -> String.valueOf(c.getOrDefault("name", "")).toLowerCase())
                        .collect(java.util.stream.Collectors.toSet());
            } catch (RuntimeException e) {
                return java.util.Set.of();
            }
        });
    }

    /**
     * Monthly recurring total of an offering's linked prices, per catalog
     * right now. Characteristic-conditioned components (TMF620
     * prodSpecCharValueUse — a Titanium Edition premium) count only when the
     * product's own characteristics match, so one offering prices every
     * colour without an SKU per variant.
     */
    @SuppressWarnings("unchecked")
    private BigDecimal monthlyFor(String offeringId, Map<String, String> characteristics,
            Map<String, String> unitCache) {
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
            if (!priceApplies(price, characteristics)) {
                continue;
            }
            total = total.add(new BigDecimal(String.valueOf(money.get("value"))));
            if (money.get("unit") != null) {
                unitCache.put(offeringId, String.valueOf(money.get("unit")));
            }
        }
        return total;
    }

    /** An unconditioned price always applies; a conditioned one needs every
     * named characteristic to hold one of its listed values. */
    @SuppressWarnings("unchecked")
    private boolean priceApplies(Map<String, Object> price, Map<String, String> characteristics) {
        if (!(price.get("prodSpecCharValueUse") instanceof List<?> conditions) || conditions.isEmpty()) {
            return true;
        }
        for (Map<String, Object> condition : (List<Map<String, Object>>) conditions) {
            String pick = characteristics == null ? null
                    : characteristics.get(String.valueOf(condition.get("name")));
            List<Map<String, Object>> allowed = condition.get("productSpecCharacteristicValue") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            if (pick == null || allowed.stream().noneMatch(v -> pick.equals(String.valueOf(v.get("value"))))) {
                return false;
            }
        }
        return true;
    }

    /** The product's configured characteristics ({color=Titanium Edition}),
     * sorted so they can key a cache. */
    @SuppressWarnings("unchecked")
    private java.util.TreeMap<String, String> charsOf(Map<String, Object> product) {
        java.util.TreeMap<String, String> chars = new java.util.TreeMap<>();
        if (product.get("productCharacteristic") instanceof List<?> list) {
            for (Map<String, Object> c : (List<Map<String, Object>>) list) {
                if (c.get("name") != null && c.get("value") != null) {
                    chars.put(String.valueOf(c.get("name")), String.valueOf(c.get("value")));
                }
            }
        }
        return chars;
    }
}
