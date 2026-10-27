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
 *
 * <p>Swap and release (#356): an order may also END benefits (TMF622 delete).
 * Ending a benefit gives its Choice back when the order completes; in the same
 * order as a new pick that is a SWAP — the new pick may spend the Choice the
 * ending frees, and the old benefit ends only once the new one is active.
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
        List<ChoicesClient.Source> replaces = endedBenefits(leafItems);
        for (Item b : benefits) {
            ChoicesClient.HoldOutcome outcome = ledger.hold(ownerPartyId, orderId, b.itemId(), b.offeringId(),
                    b.name(), b.cost() * b.quantity(), replaces);
            if (!outcome.ok()) {
                ledger.release(orderId);
                throw new ChoicesConflictException(outcome.message());
            }
        }
    }

    /** The order completed: what it held is in use for good, its plans add Choices, its endings give back. */
    void settle(List<Map<String, Object>> leafItems, String orderId, String ownerPartyId) {
        List<Item> all = items(leafItems);
        List<ChoicesClient.Source> ended = endedBenefits(leafItems);
        if (ownerPartyId == null || (ended.isEmpty() && all.stream().noneMatch(i -> i.cost() > 0 || i.grants() > 0))) {
            return;
        }
        List<ChoicesClient.Grant> grants = all.stream().filter(i -> i.grants() > 0)
                .map(i -> new ChoicesClient.Grant(i.itemId(), i.offeringId(), i.name(), i.grants() * i.quantity()))
                .toList();
        boolean swap = all.stream().anyMatch(i -> i.cost() > 0);
        List<ChoicesClient.Ending> endings = ended.stream()
                .map(e -> new ChoicesClient.Ending(e.orderId(), e.orderItemId(), swap)).toList();
        ledger.settle(orderId, ownerPartyId, grants, endings);
    }

    /** Is this order a swap — does it end a benefit and pick another? (for the swap-limit rule) */
    boolean isSwap(List<Map<String, Object>> leafItems) {
        return !endedBenefits(leafItems).isEmpty() && items(leafItems).stream().anyMatch(i -> i.cost() > 0);
    }

    ChoicesClient.Swaps swaps(String ownerPartyId) {
        return ledger.swaps(ownerPartyId);
    }

    /**
     * The benefits this order ENDS (TMF622 delete items whose offering costs
     * Choices), each named by the order item that created it — the product's
     * own lineage, which ordering copied onto the delete item from inventory.
     */
    @SuppressWarnings("unchecked")
    private List<ChoicesClient.Source> endedBenefits(List<Map<String, Object>> leafItems) {
        List<ChoicesClient.Source> out = new ArrayList<>();
        for (Map<String, Object> item : leafItems) {
            if (!"delete".equalsIgnoreCase(String.valueOf(item.get("action")))
                    || "cancelled".equals(item.get("state"))) {
                continue;   // not an ending, or a swap's ending that did not happen
            }
            Object refId = item.get("productOffering") instanceof Map<?, ?> ref ? ref.get("id") : null;
            if (refId == null || catalog.findOfferingDetail(refId.toString())
                    .map(d -> positive(d.get("choiceCost"))).orElse(0) == 0) {
                continue;
            }
            Object lineage = item.get("product") instanceof Map<?, ?> p ? p.get("productOrderItem") : null;
            if (lineage instanceof List<?> refs && !refs.isEmpty() && refs.get(0) instanceof Map<?, ?> first
                    && first.get("productOrderId") != null && first.get("orderItemId") != null) {
                out.add(new ChoicesClient.Source(first.get("productOrderId").toString(),
                        first.get("orderItemId").toString()));
            }
        }
        return out;
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
