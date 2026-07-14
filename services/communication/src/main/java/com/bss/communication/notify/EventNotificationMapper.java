package com.bss.communication.notify;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The editorial desk: which domain events deserve a customer notification,
 * and what they say. Everything else in the stream is ignored on purpose —
 * customers get signal, systems keep the full firehose.
 */
@Component
public class EventNotificationMapper {

    public record Notification(String partyId, String subject, String content) {
    }

    @SuppressWarnings("unchecked")
    public List<Notification> map(String eventType, Map<String, Object> event) {
        if (event == null) {
            return List.of();
        }
        return switch (eventType) {
            case "ProductOrderCreateEvent" -> resource(event, "productOrder")
                    .map(this::orderCreated).orElse(List.of());
            case "ProductOrderStateChangeEvent" -> resource(event, "productOrder")
                    .map(this::orderStateChanged).orElse(List.of());
            case "CustomerBillCreateEvent" -> one(resource(event, "customerBill").flatMap(bill ->
                    customer(bill).map(party -> new Notification(party,
                            "Your bill is ready",
                            "Bill " + bill.getOrDefault("billNo", "") + " is ready: "
                                    + money(bill.get("amountDue")) + ". Pay it under My bills."))));
            case "TroubleTicketStateChangeEvent" -> one(resource(event, "troubleTicket").flatMap(ticket ->
                    "resolved".equals(ticket.get("status"))
                            ? customer(ticket).map(party -> new Notification(party,
                                    "Ticket resolved",
                                    "Good news — \"" + ticket.getOrDefault("name", "your ticket")
                                            + "\" is resolved. Close it under Support if all is well."))
                            : Optional.empty()));
            case "ShoppingCartAbandonedEvent" -> one(resource(event, "shoppingCart").flatMap(cart ->
                    customer(cart).map(party -> new Notification(party,
                            "Still thinking it over?",
                            "You left " + firstItemName(cart)
                                    + " in your cart. It's saved — pick up right where you stopped."))));
            case "AppointmentCreateEvent" -> one(resource(event, "appointment").flatMap(appt ->
                    customer(appt).map(party -> new Notification(party,
                            "Installer booked",
                            "A technician is booked for "
                                    + validForStart(appt) + ". We'll ring the bell."))));
            // gifting & rollover: generosity should be visible, both ways
            case "DataGiftEvent" -> resource(event, "dataGift").map(gift -> {
                String amount = gift.get("amount") + " " + gift.getOrDefault("units", "GB");
                List<Notification> out = new java.util.ArrayList<>();
                partyIn(gift, "receiver").ifPresent(to -> partyIn(gift, "giver").ifPresent(from -> {
                    out.add(new Notification(to, "You received a data gift",
                            nameIn(gift, "giver") + " gifted you " + amount + " — it's on your meter now."));
                    out.add(new Notification(from, "Gift sent",
                            "You gifted " + amount + " to " + nameIn(gift, "receiver") + ". Nice."));
                }));
                return out;
            }).orElse(List.of());
            case "DataRolloverEvent" -> one(resource(event, "dataRollover").flatMap(roll ->
                    customer(roll).map(party -> new Notification(party,
                            "Your unused data rolled over",
                            roll.get("amount") + " " + roll.getOrDefault("units", "GB")
                                    + " you didn't use this month is yours next month. Use it or gift it."))));
            default -> List.of();
        };
    }

    /**
     * ASK-TO-BUY: a held family top-up speaks to BOTH sides — the requester
     * hears it went for approval, the payer gets the ask. A family-funded
     * instant top-up tells the payer money moved. Everything else keeps the
     * classic "order received".
     */
    private List<Notification> orderCreated(Map<String, Object> order) {
        Optional<String> customer = customer(order);
        Optional<String> payer = payer(order);
        boolean familyFunded = payer.isPresent() && !payer.equals(customer);
        if ("held".equals(order.get("state")) && familyFunded) {
            List<Notification> out = new java.util.ArrayList<>();
            customer.ifPresent(party -> out.add(new Notification(party,
                    "Sent for approval",
                    "Your top-up needs a family admin's OK — we asked them. You'll hear the moment they decide.")));
            payer.ifPresent(party -> out.add(new Notification(party,
                    "Approval needed",
                    "A family member asks to buy a top-up. Approve or decline it on your Family page.")));
            return out;
        }
        List<Notification> out = new java.util.ArrayList<>();
        customer.ifPresent(party -> out.add(new Notification(party,
                "Order received",
                "We got your order: " + order.getOrDefault("description", "your order")
                        + ". We'll tell you the moment it's ready.")));
        if (familyFunded && "Top-ups".equals(order.get("category"))) {
            payer.ifPresent(party -> out.add(new Notification(party,
                    "Family top-up bought",
                    "A family member bought a top-up within their allowance — it bills to you.")));
        }
        return out;
    }

    private List<Notification> orderStateChanged(Map<String, Object> order) {
        boolean familyTopup = "Top-ups".equals(order.get("category"))
                && payer(order).isPresent() && !payer(order).equals(customer(order));
        return switch (String.valueOf(order.get("state"))) {
            case "completed" -> one(customer(order).map(party -> new Notification(party,
                    "Order complete",
                    "Done! " + order.getOrDefault("description", "Your order") + " is active. Enjoy.")));
            case "acknowledged" -> familyTopup
                    ? one(customer(order).map(party -> new Notification(party,
                            "Approved",
                            "Your family admin approved the top-up — it's on its way.")))
                    : List.of();
            case "cancelled" -> familyTopup
                    ? one(customer(order).map(party -> new Notification(party,
                            "Not this time",
                            "Your family admin declined the top-up. Talk to them — or use a data gift.")))
                    : List.of();
            default -> List.of();
        };
    }

    private static List<Notification> one(Optional<Notification> n) {
        return n.map(List::of).orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private Optional<String> payer(Map<String, Object> resource) {
        if (resource.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && "payer".equalsIgnoreCase(String.valueOf(ref.get("role")))
                        && ref.get("id") != null) {
                    return Optional.of(String.valueOf(ref.get("id")));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> partyIn(Map<String, Object> resource, String key) {
        return resource.get(key) instanceof Map<?, ?> m && m.get("id") != null
                ? Optional.of(String.valueOf(m.get("id"))) : Optional.empty();
    }

    private String nameIn(Map<String, Object> resource, String key) {
        return resource.get(key) instanceof Map<?, ?> m && m.get("name") != null
                ? String.valueOf(m.get("name")) : "a family member";
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> resource(Map<String, Object> event, String key) {
        return event.get(key) instanceof Map<?, ?> m
                ? Optional.of((Map<String, Object>) m)
                : Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Optional<String> customer(Map<String, Object> resource) {
        if (resource.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && "customer".equalsIgnoreCase(String.valueOf(ref.get("role")))
                        && ref.get("id") != null) {
                    return Optional.of(String.valueOf(ref.get("id")));
                }
            }
        }
        return Optional.empty();
    }

    private String money(Object amount) {
        if (amount instanceof Map<?, ?> m && m.get("value") != null) {
            Object unit = m.get("unit");
            return m.get("value") + " " + (unit == null ? "" : unit);
        }
        return "see your bill";
    }

    private String firstItemName(Map<String, Object> cart) {
        if (cart.get("cartItem") instanceof List<?> items && !items.isEmpty()
                && items.get(0) instanceof Map<?, ?> first && first.get("name") != null) {
            return String.valueOf(first.get("name"));
        }
        return "something";
    }

    private String validForStart(Map<String, Object> appt) {
        if (appt.get("validFor") instanceof Map<?, ?> v && v.get("startDateTime") != null) {
            return String.valueOf(v.get("startDateTime"));
        }
        return "the agreed time";
    }
}
