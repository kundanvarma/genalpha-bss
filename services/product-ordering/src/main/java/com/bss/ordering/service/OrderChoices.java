package com.bss.ordering.service;

import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.ChoicesClient;
import com.bss.ordering.exception.ChoicesConflictException;
import com.bss.ordering.exception.OrderValidationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where an order meets the household's Choices (#354). The catalog says what
 * an offering is worth in Choices — a plan's {@code choicesGranted}, a
 * benefit's {@code choiceCost}, house fields beside TMF620 — and the ledger
 * keeps the count. Placing an order holds what its benefits cost (refused with
 * 409 when too few are left); completing it settles (commits the holds, adds
 * what its plans grant); cancelling releases. An order with neither a benefit
 * nor a granting plan never calls the ledger.
 */
@Component
class OrderChoices {

    private final CatalogClient catalog;
    private final ChoicesClient ledger;

    OrderChoices(CatalogClient catalog, ChoicesClient ledger) {
        this.catalog = catalog;
        this.ledger = ledger;
    }

    /** One item of the order and what it is worth in Choices. */
    private record Item(String itemId, String offeringId, String name, int quantity, int cost, int grants) {
    }

    /** Hold the Choices the order's benefits cost; on any refusal nothing stays held. */
    void hold(List<Map<String, Object>> leafItems, String orderId, String ownerPartyId) {
        List<Item> benefits = items(leafItems).stream().filter(i -> i.cost() > 0).toList();
        if (benefits.isEmpty()) {
            return;
        }
        if (ownerPartyId == null) {
            throw new OrderValidationException("a benefit is picked for a customer — name the customer");
        }
        for (Item b : benefits) {
            ChoicesClient.HoldOutcome outcome = ledger.hold(ownerPartyId, orderId, b.itemId(), b.offeringId(),
                    b.name(), b.cost() * b.quantity());
            if (!outcome.ok()) {
                ledger.release(orderId);
                throw new ChoicesConflictException(outcome.message());
            }
        }
    }

    /** The order completed: what it held is in use for good, and its plans add Choices. */
    void settle(List<Map<String, Object>> leafItems, String orderId, String ownerPartyId) {
        List<Item> all = items(leafItems);
        if (ownerPartyId == null || all.stream().noneMatch(i -> i.cost() > 0 || i.grants() > 0)) {
            return;
        }
        List<ChoicesClient.Grant> grants = all.stream().filter(i -> i.grants() > 0)
                .map(i -> new ChoicesClient.Grant(i.itemId(), i.offeringId(), i.name(), i.grants() * i.quantity()))
                .toList();
        ledger.settle(orderId, ownerPartyId, grants);
    }

    /** The order was cancelled: whatever it held goes back. */
    void release(List<Map<String, Object>> leafItems, String orderId) {
        if (items(leafItems).stream().anyMatch(i -> i.cost() > 0)) {
            ledger.release(orderId);
        }
    }

    /** The items that ADD something (an ending or a plan change spends and grants nothing here). */
    private List<Item> items(List<Map<String, Object>> leafItems) {
        List<Item> out = new ArrayList<>();
        for (Map<String, Object> item : leafItems) {
            Object action = item.get("action");
            if (action != null && !"add".equalsIgnoreCase(String.valueOf(action))) {
                continue;
            }
            Object itemId = item.get("id");
            Object refId = item.get("productOffering") instanceof Map<?, ?> ref ? ref.get("id") : null;
            if (itemId == null || refId == null) {
                continue;   // an item without ids cannot be held or granted against
            }
            Map<?, ?> ref = (Map<?, ?>) item.get("productOffering");
            String offeringId = refId.toString();
            Optional<Map<String, Object>> detail = catalog.findOfferingDetail(offeringId);
            int cost = detail.map(d -> positive(d.get("choiceCost"))).orElse(0);
            int grants = detail.map(d -> positive(d.get("choicesGranted"))).orElse(0);
            if (cost == 0 && grants == 0) {
                continue;
            }
            String name = ref.get("name") != null ? String.valueOf(ref.get("name"))
                    : detail.map(d -> String.valueOf(d.get("name"))).orElse(offeringId);
            int quantity = item.get("quantity") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            out.add(new Item(itemId.toString(), offeringId, name, quantity, cost, grants));
        }
        return out;
    }

    private static int positive(Object value) {
        return value instanceof Number n && n.intValue() > 0 ? n.intValue() : 0;
    }
}
