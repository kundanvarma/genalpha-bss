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
        if (quote.getNarrative() != null) map.put("narrative", quote.getNarrative());
        if (quote.getProductOrderId() != null) {
            map.put("productOrder", Map.of("id", quote.getProductOrderId()));
        }
        map.put("@type", "Quote");
        return map;
    }
}
