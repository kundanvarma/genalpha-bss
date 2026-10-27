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
    private final com.bss.ordering.client.InventoryClient inventory;

    OrderChoices(CatalogClient catalog, ChoicesClient ledger, com.bss.ordering.client.InventoryClient inventory) {
        this.catalog = catalog;
        this.ledger = ledger;
        this.inventory = inventory;
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
                    b.name(), b.cost() * b.quantity(), replaces, holdMonthsOf(b.offeringId()));
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
        List<PlanChange> plans = planChanges(leafItems);
        if (ownerPartyId == null || (ended.isEmpty() && plans.isEmpty()
                && all.stream().noneMatch(i -> i.cost() > 0 || i.grants() > 0))) {
            return;
        }
        List<ChoicesClient.Grant> grants = all.stream().filter(i -> i.grants() > 0)
                .map(i -> new ChoicesClient.Grant(i.itemId(), i.offeringId(), i.name(), i.grants() * i.quantity()))
                .toList();
        boolean swap = all.stream().anyMatch(i -> i.cost() > 0);
        List<ChoicesClient.Ending> endings = ended.stream()
                .map(e -> new ChoicesClient.Ending(e.orderId(), e.orderItemId(), swap)).toList();
        // what each changed plan now grants (0 for a plan that ended)
        List<ChoicesClient.Regrant> regrants = plans.stream()
                .map(p -> new ChoicesClient.Regrant(p.source().orderId(), p.source().orderItemId(),
                        p.ending() ? null : p.offeringId(), p.ending() ? null : p.offeringName(), p.after()))
                .toList();
        ledger.settle(orderId, ownerPartyId, grants, endings, regrants);
    }

    /** Is this order a swap — does it end a benefit and pick another? (for the swap-limit rule) */
    boolean isSwap(List<Map<String, Object>> leafItems) {
        return !endedBenefits(leafItems).isEmpty() && items(leafItems).stream().anyMatch(i -> i.cost() > 0);
    }

    ChoicesClient.Pool pool(String ownerPartyId) {
        return ledger.pool(ownerPartyId);
    }

    /* ------------------------------------------------ plan lifecycle (#357) ---- */

    /** A granting plan this order changes: the grant it names, and what it gives after the order. */
    private record PlanChange(ChoicesClient.Source source, String offeringId, String offeringName, int after,
            boolean ending) {
    }

    /**
     * At placement, before anything is held: a held benefit cannot be ended
     * yet; a plan change that would leave fewer Choices than are in use is
     * refused with the benefits listed; and ending a granting plan (a cease, a
     * line given up) ends the newest benefits it can no longer pay for, in the
     * same order — returned as delete items for the caller to add. A held
     * benefit is never ended silently: such a cease is refused with its date.
     */
    List<Map<String, Object>> prepare(List<Map<String, Object>> leafItems, String ownerPartyId) {
        List<ChoicesClient.Source> ended = endedBenefits(leafItems);
        List<PlanChange> plans = planChanges(leafItems);
        if (ownerPartyId == null || (ended.isEmpty() && plans.isEmpty())) {
            return List.of();
        }
        ChoicesClient.Pool pool = ledger.pool(ownerPartyId);
        for (ChoicesClient.Source e : ended) {
            inUse(pool, e).filter(OrderChoices::held).ifPresent(a -> {
                throw new ChoicesConflictException(a.offeringName() + " holds its Choice until " + a.heldUntil()
                        + " — it can be released or swapped from then");
            });
        }
        if (plans.isEmpty()) {
            return List.of();
        }
        int granted = pool.granted();
        for (PlanChange p : plans) {
            int before = grantOf(pool, p.source());
            granted += p.after() - before;
        }
        int endedCost = ended.stream().map(e -> inUse(pool, e)).flatMap(java.util.Optional::stream)
                .mapToInt(ChoicesClient.InUse::cost).sum();
        int inUse = pool.inUse() - endedCost + items(leafItems).stream().mapToInt(i -> i.cost() * i.quantity()).sum();
        int deficit = inUse - granted;
        if (deficit <= 0) {
            return List.of();
        }
        List<ChoicesClient.InUse> kept = pool.allocations().stream()
                .filter(a -> ended.stream().noneMatch(e -> e.orderId().equals(a.orderId()) && e.orderItemId().equals(a.orderItemId())))
                .toList();
        if (plans.stream().noneMatch(PlanChange::ending)) {
            throw new ChoicesConflictException("This plan gives " + granted + " Choice" + (granted == 1 ? "" : "s")
                    + " and " + inUse + " are in use (" + names(kept) + ") — release " + deficit
                    + " first, then change the plan");
        }
        return endingsFor(kept, deficit, ownerPartyId);
    }

    /** The newest benefits worth {@code deficit} Choices, as delete items — none of them held. */
    private List<Map<String, Object>> endingsFor(List<ChoicesClient.InUse> kept, int deficit, String ownerPartyId) {
        List<ChoicesClient.InUse> newestFirst = new ArrayList<>(kept);
        java.util.Collections.reverse(newestFirst);   // the ledger lists them oldest first
        List<Map<String, Object>> products = inventory.activeProductsOf(ownerPartyId);
        List<Map<String, Object>> out = new ArrayList<>();
        int covered = 0;
        for (ChoicesClient.InUse a : newestFirst) {
            if (covered >= deficit) {
                break;
            }
            if (held(a)) {
                continue;
            }
            Map<String, Object> product = products.stream().filter(p -> madeBy(p, a)).findFirst().orElse(null);
            if (product == null) {
                continue;
            }
            Map<String, Object> ref = new java.util.LinkedHashMap<>();
            ref.put("id", product.get("id"));
            ref.put("name", product.get("name"));
            if (product.get("realizingService") != null) {
                ref.put("realizingService", product.get("realizingService"));
            }
            ref.put("productOrderItem", product.get("productOrderItem"));
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", "choice-ending-" + (out.size() + 1));
            item.put("action", "delete");
            item.put("product", ref);
            if (product.get("productOffering") != null) {
                item.put("productOffering", product.get("productOffering"));
            }
            out.add(item);
            covered += a.cost();
        }
        if (covered < deficit) {
            String heldNames = kept.stream().filter(OrderChoices::held)
                    .map(a -> a.offeringName() + " (until " + a.heldUntil() + ")").collect(java.util.stream.Collectors.joining(", "));
            throw new ChoicesConflictException("Ending this plan would also end benefits that are still held: "
                    + heldNames + " — they can end from those dates");
        }
        return out;
    }

    /** Plan items this order modifies or ends that bought Choices (named by the plan product's lineage). */
    private List<PlanChange> planChanges(List<Map<String, Object>> leafItems) {
        List<PlanChange> out = new ArrayList<>();
        for (Map<String, Object> item : leafItems) {
            String action = String.valueOf(item.get("action"));
            boolean ending = "delete".equalsIgnoreCase(action);
            if ((!ending && !"modify".equalsIgnoreCase(action)) || "cancelled".equals(item.get("state"))) {
                continue;
            }
            ChoicesClient.Source source = lineageOf(item);
            Object refId = item.get("productOffering") instanceof Map<?, ?> ref ? ref.get("id") : null;
            if (source == null || refId == null) {
                continue;
            }
            Optional<Map<String, Object>> detail = catalog.findOfferingDetail(refId.toString());
            if (ending && detail.map(d -> positive(d.get("choiceCost"))).orElse(0) > 0) {
                continue;   // ending a BENEFIT, not a plan
            }
            int after = ending ? 0 : detail.map(d -> positive(d.get("choicesGranted"))).orElse(0);
            String name = detail.map(d -> String.valueOf(d.get("name"))).orElse(refId.toString());
            out.add(new PlanChange(source, refId.toString(), name, after, ending));
        }
        return out;
    }

    private static int grantOf(ChoicesClient.Pool pool, ChoicesClient.Source s) {
        return pool.grants().stream().filter(g -> s.orderId().equals(g.orderId()) && s.orderItemId().equals(g.orderItemId()))
                .mapToInt(ChoicesClient.GrantView::choices).sum();
    }

    private static Optional<ChoicesClient.InUse> inUse(ChoicesClient.Pool pool, ChoicesClient.Source s) {
        return pool.allocations().stream()
                .filter(a -> s.orderId().equals(a.orderId()) && s.orderItemId().equals(a.orderItemId())).findFirst();
    }

    private static boolean held(ChoicesClient.InUse a) {
        return a.heldUntil() != null && java.time.LocalDate.parse(a.heldUntil())
                .isAfter(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
    }

    private static boolean madeBy(Map<String, Object> product, ChoicesClient.InUse a) {
        return product.get("productOrderItem") instanceof List<?> refs && refs.stream().anyMatch(r -> r instanceof Map<?, ?> m
                && a.orderId().equals(m.get("productOrderId")) && a.orderItemId().equals(m.get("orderItemId")));
    }

    private static String names(List<ChoicesClient.InUse> in) {
        return in.stream().map(ChoicesClient.InUse::offeringName).collect(java.util.stream.Collectors.joining(", "));
    }

    private static ChoicesClient.Source lineageOf(Map<String, Object> item) {
        Object lineage = item.get("product") instanceof Map<?, ?> p ? p.get("productOrderItem") : null;
        if (lineage instanceof List<?> refs && !refs.isEmpty() && refs.get(0) instanceof Map<?, ?> first
                && first.get("productOrderId") != null && first.get("orderItemId") != null) {
            return new ChoicesClient.Source(first.get("productOrderId").toString(), first.get("orderItemId").toString());
        }
        return null;
    }

    private Integer holdMonthsOf(String offeringId) {
        int months = catalog.findOfferingDetail(offeringId).map(d -> positive(d.get("choiceHoldMonths"))).orElse(0);
        return months > 0 ? months : null;
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
            ChoicesClient.Source source = lineageOf(item);
            if (source != null) {
                out.add(source);
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
