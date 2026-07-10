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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final DomainEventPublisher events;
    private final PartyScope partyScope;

    public BillingRunService(CustomerBillRepository bills, AppliedBillingRateRepository rates,
            DownstreamClients.InventoryClient inventory, DownstreamClients.CatalogClient catalog,
            DomainEventPublisher events, PartyScope partyScope) {
        this.bills = bills;
        this.rates = rates;
        this.inventory = inventory;
        this.catalog = catalog;
        this.events = events;
        this.partyScope = partyScope;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> run() {
        if (partyScope.scopedPartyId().isPresent()) {
            throw new BadRequestException("billing runs are a back-office operation");
        }
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

        Map<String, BigDecimal> priceCache = new HashMap<>();
        Map<String, String> unitCache = new HashMap<>();
        int created = 0;
        int skipped = 0;
        for (Map.Entry<String, List<Map<String, Object>>> owner : byOwner.entrySet()) {
            if (bills.existsByOwnerPartyIdAndPeriodStart(owner.getKey(), periodStart)) {
                skipped++;
                continue;
            }
            List<AppliedBillingRate> billRates = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            String unit = "EUR";
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
                rate.setName(String.valueOf(product.getOrDefault("name", offeringId)));
                rate.setRateType("recurringCharge");
                rate.setAmountValue(monthly);
                rate.setAmountUnit(unit);
                rate.setProductJson("{\"id\":\"" + product.get("id") + "\"}");
                rate.setOwnerPartyId(owner.getKey());
                rate.setRateDate(OffsetDateTime.now());
                billRates.add(rate);
                total = total.add(monthly);
            }
            if (billRates.isEmpty()) {
                continue;
            }
            CustomerBill bill = new CustomerBill();
            String id = UUID.randomUUID().toString();
            bill.setId(id);
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
