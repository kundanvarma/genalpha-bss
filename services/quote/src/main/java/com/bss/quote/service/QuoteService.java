package com.bss.quote.service;

import com.bss.quote.api.ApiConstants;
import com.bss.quote.client.DownstreamClients;
import com.bss.quote.entity.Quote;
import com.bss.quote.events.DomainEventPublisher;
import com.bss.quote.exception.BadRequestException;
import com.bss.quote.exception.ConflictException;
import com.bss.quote.exception.NotFoundException;
import com.bss.quote.repository.QuoteRepository;
import com.bss.quote.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF648, the commercial half of the intent loop: the OSS's proposal
 * priced. A quote is born FROM an intent — every proposed service item is
 * matched to a catalog offering with its real prices and token allowances,
 * an optional AI narrative explains the deal in the customer's language,
 * and acceptance hands straight into product ordering. Lead to order,
 * no swivel chairs.
 */
@Service
public class QuoteService {

    private static final TypeReference<List<Map<String, Object>>> ITEMS = new TypeReference<>() {
    };

    private final QuoteRepository quotes;
    private final DownstreamClients downstream;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public QuoteService(QuoteRepository quotes, DownstreamClients downstream,
            DomainEventPublisher events, TenantScope tenantScope, ObjectMapper objectMapper) {
        this.quotes = quotes;
        this.downstream = downstream;
        this.events = events;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> createFromIntent(Map<String, Object> dto) {
        if (dto.get("intentId") == null) {
            throw new BadRequestException("intentId is required — quotes are born from intents here");
        }
        Map<String, Object> intent = downstream.intent(String.valueOf(dto.get("intentId")));
        if (!(intent.get("intentReport") instanceof Map<?, ?> report)
                || !Boolean.TRUE.equals(report.get("feasible"))) {
            throw new ConflictException("the intent is not feasibility-checked; nothing to quote");
        }

        Map<String, Map<String, Object>> catalog = new LinkedHashMap<>();
        for (Map<String, Object> offering : downstream.offerings()) {
            catalog.put(String.valueOf(offering.get("name")), offering);
        }
        List<Map<String, Object>> allowances = downstream.allowances();

        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal monthly = BigDecimal.ZERO;
        String currency = "EUR";
        for (Object proposedObj : (List<?>) report.get("proposedItems")) {
            Map<?, ?> proposed = (Map<?, ?>) proposedObj;
            Map<String, Object> offering = catalog.get(String.valueOf(proposed.get("offeringName")));
            if (offering == null) {
                throw new ConflictException("proposed offering '" + proposed.get("offeringName")
                        + "' is not in the catalog");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("offering", Map.of("id", offering.get("id"), "name", offering.get("name")));
            item.put("reason", proposed.get("reason"));
            if (offering.get("productOfferingPrice") instanceof List<?> priceRefs
                    && !priceRefs.isEmpty() && priceRefs.get(0) instanceof Map<?, ?> priceRef) {
                Map<String, Object> price = downstream.offeringPrice(String.valueOf(priceRef.get("id")));
                if (price.get("price") instanceof Map<?, ?> money && money.get("value") != null) {
                    BigDecimal value = new BigDecimal(String.valueOf(money.get("value")));
                    currency = String.valueOf(money.get("unit"));
                    item.put("unitPrice", Map.of("value", value, "unit", currency,
                            "period", price.get("recurringChargePeriodType")));
                    if ("month".equals(price.get("recurringChargePeriodType"))) {
                        monthly = monthly.add(value);
                    }
                }
            }
            // Token economics on the line item: what is included, what overage costs.
            for (Map<String, Object> allowance : allowances) {
                if (allowance.get("productOffering") instanceof Map<?, ?> ref
                        && String.valueOf(offering.get("id")).equals(String.valueOf(ref.get("id")))) {
                    item.put("allowance", Map.of(
                            "usageType", allowance.get("usageType"),
                            "included", allowance.get("allowance"),
                            "overagePrice", allowance.get("overagePrice")));
                }
            }
            items.add(item);
        }

        Quote quote = new Quote();
        quote.setId(UUID.randomUUID().toString());
        quote.setTenantId(tenantScope.currentTenantId());
        quote.setHref(ApiConstants.BASE_PATH + "/quote/" + quote.getId());
        quote.setDescription(dto.get("description") == null
                ? String.valueOf(intent.get("name")) : String.valueOf(dto.get("description")));
        quote.setState(Quote.IN_PROGRESS);
        quote.setIntentId(String.valueOf(dto.get("intentId")));
        if (intent.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> party) {
            quote.setOwnerPartyId(String.valueOf(party.get("id")));
        }
        writeItems(quote, items);
        quote.setMonthlyTotal(monthly);
        quote.setCurrency(currency);
        quote.setNarrative(downstream.quoteNarrative(Map.of(
                "description", quote.getDescription(),
                "items", items,
                "monthlyTotal", monthly,
                "currency", currency)));
        quote.setCreatedAt(OffsetDateTime.now());
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        Map<String, Object> result = toMap(quote);
        events.publish("QuoteCreateEvent", "quote", result);
        return result;
    }

    /**
     * CPQ C1 — the opportunity → quote hand-off. Build a quote from a
     * developed opportunity's negotiated line items: MRR from the recurring
     * lines, one-off from the rest. State opens at inProgress like any quote.
     */
    @Transactional
    public Map<String, Object> createFromLineItems(String description, String ownerPartyId,
            String currency, List<Map<String, Object>> lineItems) {
        String cur = currency == null ? "USD" : currency;
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal monthly = BigDecimal.ZERO;
        BigDecimal oneTime = BigDecimal.ZERO;
        for (Map<String, Object> li : lineItems) {
            boolean recurring = !Boolean.FALSE.equals(li.get("recurring"));
            int qty = li.get("quantity") == null ? 1 : ((Number) li.get("quantity")).intValue();
            BigDecimal unit = li.get("unitPrice") == null ? BigDecimal.ZERO
                    : new BigDecimal(String.valueOf(li.get("unitPrice")));
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(qty));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("offering", Map.of(
                    "id", li.get("offeringId") == null ? "" : li.get("offeringId"),
                    "name", li.get("offeringName")));
            item.put("quantity", qty);
            item.put("unitPrice", Map.of("value", unit, "unit", cur,
                    "period", recurring ? "month" : "oneTime"));
            item.put("recurring", recurring);
            items.add(item);
            if (recurring) monthly = monthly.add(lineTotal); else oneTime = oneTime.add(lineTotal);
        }
        Quote quote = new Quote();
        quote.setId(UUID.randomUUID().toString());
        quote.setTenantId(tenantScope.currentTenantId());
        quote.setHref(ApiConstants.BASE_PATH + "/quote/" + quote.getId());
        quote.setDescription(description == null ? "Opportunity quote" : description);
        quote.setState(Quote.IN_PROGRESS);
        quote.setOwnerPartyId(ownerPartyId);
        writeItems(quote, items);
        quote.setMonthlyTotal(monthly);
        quote.setOneTimeTotal(oneTime);
        quote.setCurrency(cur);
        quote.setCreatedAt(OffsetDateTime.now());
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        Map<String, Object> result = toMap(quote);
        events.publish("QuoteCreateEvent", "quote", result);
        return result;
    }

    /** A branded, printable quote document (HTML) the rep can send. */
    @Transactional(readOnly = true)
    public String renderDocument(String id) {
        Quote quote = own(id);
        StringBuilder rows = new StringBuilder();
        for (Map<String, Object> item : readItems(quote)) {
            Object off = item.get("offering");
            String name = off instanceof Map<?, ?> m ? String.valueOf(m.get("name")) : "—";
            Object up = item.get("unitPrice");
            String price = up instanceof Map<?, ?> m
                    ? m.get("value") + " " + m.get("unit") + "/" + m.get("period") : "—";
            String qty = String.valueOf(item.getOrDefault("quantity", 1));
            rows.append("<tr><td>").append(esc(name)).append("</td><td style=\"text-align:right\">")
                    .append(esc(qty)).append("</td><td style=\"text-align:right\">")
                    .append(esc(price)).append("</td></tr>");
        }
        String cur = quote.getCurrency();
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Quote "
                + esc(quote.getId()) + "</title><style>body{font-family:system-ui,sans-serif;"
                + "max-width:720px;margin:2rem auto;color:#1a1a1a}h1{font-size:1.4rem}"
                + "table{width:100%;border-collapse:collapse;margin:1rem 0}"
                + "th,td{padding:0.5rem;border-bottom:1px solid #ddd;text-align:left}"
                + ".totals{margin-top:1rem;font-size:1.05rem}.dim{color:#666}</style></head><body>"
                + "<h1>Quotation</h1><p class=\"dim\">" + esc(quote.getDescription()) + "</p>"
                + "<p class=\"dim\">Quote " + esc(quote.getId()) + " · status " + esc(quote.getState()) + "</p>"
                + "<table><thead><tr><th>Item</th><th style=\"text-align:right\">Qty</th>"
                + "<th style=\"text-align:right\">Price</th></tr></thead><tbody>" + rows + "</tbody></table>"
                + "<div class=\"totals\"><b>Monthly (recurring): " + quote.getMonthlyTotal() + " " + esc(cur)
                + "</b><br><b>One-time: " + quote.getOneTimeTotal() + " " + esc(cur) + "</b></div>"
                + "<p class=\"dim\" style=\"margin-top:2rem\">This quotation is valid subject to the terms of service.</p>"
                + "</body></html>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll() {
        return quotes.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        return toMap(own(id));
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Quote quote = own(id);
        if (patch.get("state") != null) {
            String target = String.valueOf(patch.get("state"));
            if (!List.of(Quote.APPROVED, Quote.REJECTED).contains(target)
                    || !Quote.IN_PROGRESS.equals(quote.getState())) {
                throw new ConflictException("only inProgress quotes move to approved/rejected");
            }
            quote.setState(target);
        }
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        Map<String, Object> result = toMap(quote);
        events.publish("QuoteStateChangeEvent", "quote", result);
        return result;
    }

    /** The handoff: an approved quote becomes a product order, atomically once. */
    @Transactional
    public Map<String, Object> accept(String id) {
        Quote quote = own(id);
        if (!Quote.APPROVED.equals(quote.getState())) {
            throw new ConflictException("only approved quotes can be accepted");
        }
        List<Map<String, Object>> orderItems = new ArrayList<>();
        for (Map<String, Object> item : readItems(quote)) {
            Map<?, ?> offering = (Map<?, ?>) item.get("offering");
            orderItems.add(Map.of("action", "add",
                    "productOffering", Map.of("id", offering.get("id"), "name", offering.get("name"))));
        }
        Map<String, Object> order = downstream.placeOrder(Map.of(
                "productOrderItem", orderItems,
                "relatedParty", List.of(Map.of(
                        "id", quote.getOwnerPartyId() == null ? "unknown" : quote.getOwnerPartyId(),
                        "role", "customer"))));
        quote.setProductOrderId(String.valueOf(order.get("id")));
        quote.setState(Quote.ACCEPTED);
        quote.setLastUpdate(OffsetDateTime.now());
        quotes.save(quote);
        Map<String, Object> result = toMap(quote);
        events.publish("QuoteStateChangeEvent", "quote", result);
        return result;
    }

    private Quote own(String id) {
        return quotes.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Quote", id));
    }

    private void writeItems(Quote quote, List<Map<String, Object>> items) {
        try {
            quote.setItems(objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            throw new IllegalStateException("items serialization failed", e);
        }
    }

    private List<Map<String, Object>> readItems(Quote quote) {
        try {
            return objectMapper.readValue(quote.getItems(), ITEMS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> toMap(Quote quote) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", quote.getId());
        map.put("href", quote.getHref());
        map.put("description", quote.getDescription());
        map.put("state", quote.getState());
        if (quote.getIntentId() != null) {
            map.put("intent", Map.of("id", quote.getIntentId()));
        }
        if (quote.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", quote.getOwnerPartyId(), "role", "customer")));
        }
        map.put("quoteItem", readItems(quote));
        map.put("quoteTotalPrice", Map.of("value", quote.getMonthlyTotal(),
                "unit", quote.getCurrency(), "period", "month"));
        if (quote.getOneTimeTotal() != null && quote.getOneTimeTotal().signum() != 0) {
            map.put("quoteOneTimePrice", Map.of("value", quote.getOneTimeTotal(),
                    "unit", quote.getCurrency(), "period", "oneTime"));
        }
        if (quote.getNarrative() != null) map.put("narrative", quote.getNarrative());
        if (quote.getProductOrderId() != null) {
            map.put("productOrder", Map.of("id", quote.getProductOrderId()));
        }
        map.put("@type", "Quote");
        return map;
    }
}
