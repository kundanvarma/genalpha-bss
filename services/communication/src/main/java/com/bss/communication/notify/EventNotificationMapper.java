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

    public record Notification(String partyId, String subject, String content,
            String attachmentName, String attachmentBase64) {
        public Notification(String partyId, String subject, String content) {
            this(partyId, subject, content, null, null);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Notification> map(String eventType, Map<String, Object> event) {
        if (event == null) {
            return List.of();
        }
        return switch (eventType) {
            case "ProductOrderCreateEvent" -> resource(event, "productOrder")
                    .map(this::orderCreated).orElse(List.of());
            // a changed credential is never silent: the owner hears about it
            case "SimPinResetEvent" -> one(resource(event, "sim").flatMap(sim ->
                    customer(sim).map(party -> new Notification(party,
                            "Your SIM PIN was changed",
                            "The PIN on your SIM " + sim.getOrDefault("iccid", "") + " was just changed."
                            + " If this was not you, contact us immediately."))));
            case "InstallmentPlanCreatedEvent" -> one(resource(event, "installmentPlan").flatMap(plan ->
                    customer(plan).map(party -> new Notification(party,
                            "Your bill is split into " + plan.getOrDefault("installments", "?") + " payments",
                            "Bill " + plan.getOrDefault("billNo", "") + ": "
                            + plan.getOrDefault("installments", "?") + " monthly payments of "
                            + plan.getOrDefault("amountPer", "?") + " " + plan.getOrDefault("currency", "")
                            + " (last " + plan.getOrDefault("lastAmount", "?") + "). First due "
                            + String.valueOf(plan.getOrDefault("nextDueAt", "")).substring(0, 10) + "."))));
            case "DisputeOpenedEvent" -> one(resource(event, "dispute").flatMap(d ->
                    customer(d).map(party -> new Notification(party,
                            "We are looking into your dispute",
                            "You disputed a charge on bill " + d.getOrDefault("billNo", "")
                            + " (\"" + d.getOrDefault("reason", "") + "\"). Collection is paused"
                            + " while we investigate — we will come back with a decision."))));
            case "DisputeResolvedEvent" -> one(resource(event, "dispute").flatMap(d ->
                    customer(d).map(party -> new Notification(party,
                            "Your dispute was resolved",
                            "credited".equals(d.get("status"))
                                ? ("settled".equals(d.get("billState"))
                                    ? "We refunded " + d.getOrDefault("creditAmount", "?")
                                        + " to your card for bill " + d.getOrDefault("billNo", "")
                                        + ". Thank you for flagging it."
                                    : "We credited " + d.getOrDefault("creditAmount", "?")
                                        + " on bill " + d.getOrDefault("billNo", "")
                                        + " — the amount due is reduced. Thank you for flagging it.")
                                : "We reviewed bill " + d.getOrDefault("billNo", "")
                                    + " and the charge stands"
                                    + (d.get("resolutionNote") != null
                                        ? ": " + d.get("resolutionNote") : ".")
                                    + " Call us if anything is still unclear."))));
            case "PaymentRefundEvent" -> one(resource(event, "refund").flatMap(r ->
                    customer(r).map(party -> new Notification(party,
                            "Money on its way back",
                            "A refund of " + amountOf(r) + " was issued to your original"
                            + " payment method" + (r.get("reason") != null
                                ? " (" + r.get("reason") + ")" : "") + "."))));
            case "InstallmentOverdueEvent" -> one(resource(event, "installmentPlan").flatMap(plan ->
                    customer(plan).map(party -> new Notification(party,
                            "An installment is overdue",
                            "Installment " + (asInt(plan.get("paidCount")) + 1) + " of "
                            + plan.getOrDefault("installments", "?") + " for bill "
                            + plan.getOrDefault("billNo", "") + " is overdue. Please pay within "
                            + plan.getOrDefault("graceDays", "?") + " day(s) or the plan is"
                            + " cancelled and the remaining " + plan.getOrDefault("remaining", "?")
                            + " " + plan.getOrDefault("currency", "") + " falls due at once."))));
            case "InstallmentPlanBrokenEvent" -> one(resource(event, "installmentPlan").flatMap(plan ->
                    customer(plan).map(party -> new Notification(party,
                            "Your installment plan was cancelled",
                            "The plan for bill " + plan.getOrDefault("billNo", "")
                            + " was cancelled after the missed payment. The remaining "
                            + plan.getOrDefault("remaining", "?") + " "
                            + plan.getOrDefault("currency", "") + " is now due in one payment."
                            + " If you need help, call us — there is always a way."))));
            case "InstallmentPaidEvent" -> one(resource(event, "installmentPlan").flatMap(plan ->
                    customer(plan).map(party -> new Notification(party,
                            "Installment " + plan.getOrDefault("paidCount", "?") + " of "
                                    + plan.getOrDefault("installments", "?") + " received",
                            "completed".equals(plan.get("status"))
                                ? "That was the last one — bill " + plan.getOrDefault("billNo", "")
                                    + " is settled. Thank you."
                                : "We received " + plan.getOrDefault("paidAmount", "?") + " "
                                    + plan.getOrDefault("currency", "") + " for bill "
                                    + plan.getOrDefault("billNo", "") + ". Next payment of "
                                    + plan.getOrDefault("nextAmount", "?") + " due "
                                    + String.valueOf(plan.getOrDefault("nextDueAt", "")).substring(0, 10) + "."))));
            case "BillingCycleChangedEvent" -> one(resource(event, "billingCycle").flatMap(bc ->
                    customer(bc).map(party -> new Notification(party,
                            "Your billing date changed",
                            "Your billing cycle now starts on day " + bc.getOrDefault("anchorDay", "?")
                            + " of the month. It applies from your NEXT cycle — days already"
                            + " billed are never billed twice; if there is a gap, one short"
                            + " bridging bill covers exactly those days."))));
            // the bank said the money arrived and the bill closed itself —
            // the customer hears it as a thank-you, not a mystery
            case "RemittanceAppliedEvent" -> one(resource(event, "remittance").flatMap(r ->
                    customer(r).map(party -> new Notification(party,
                            "Payment received — thank you",
                            "Your bank transfer of " + r.getOrDefault("amount", "?")
                            + " arrived and bill " + r.getOrDefault("billNo", "")
                            + " is settled. Nothing more to do."))));
            case "BillDeliveryChangedEvent" -> one(resource(event, "billDelivery").flatMap(bd ->
                    customer(bd).map(party -> new Notification(party,
                            "How you get your bill changed",
                            switch (String.valueOf(bd.getOrDefault("preference", "default"))) {
                                case "paper" -> "Your bills will now also arrive by post as a"
                                        + " printed invoice. The in-app bill stays, always.";
                                case "einvoice" -> "Your bills will now also be delivered as an"
                                        + " e-invoice through our distribution partner.";
                                case "digital" -> "Your bills are now digital-only: in-app"
                                        + " (and email where configured). Nothing by post.";
                                default -> "Your bill delivery is back to the standard"
                                        + " arrangement for your operator.";
                            } + " If you did not request this, contact us."))));
            case "HouseholdInviteEvent" -> one(resource(event, "householdInvite").flatMap(inv ->
                    customer(inv).map(party -> new Notification(party,
                            "You are invited to a family plan",
                            inv.getOrDefault("payerName", "Someone") + " wants to add you to their"
                            + " family — they would pay for your subscriptions. Accept or decline"
                            + " on your Family page; nothing changes until you do."))));
            case "HouseholdJoinedEvent" -> one(resource(event, "householdJoined").flatMap(j ->
                    customer(j).map(party -> new Notification(party,
                            j.getOrDefault("memberName", "Your new family member") + " joined your family",
                            "They accepted your invitation. You can now order plans for them from"
                            + " your Family hub — everything they order through the family bills"
                            + " to you, itemized per person."))));
            case "ServiceTransferredEvent" -> resource(event, "serviceTransfer").map(t -> {
                String line = t.getOrDefault("name", "A line") + (t.get("number") != null
                        ? " (" + t.get("number") + ")" : "");
                List<Notification> out = new java.util.ArrayList<>();
                partyWithRole(t, "receiver").ifPresent(to -> out.add(new Notification(to,
                        "A line was transferred to you",
                        line + " is now yours. The SIM in the device keeps working; its PUK"
                        + " and PIN controls are in your shop. Welcome aboard.")));
                partyWithRole(t, "giver").ifPresent(from -> out.add(new Notification(from,
                        "Your line was transferred",
                        line + " now belongs to its new owner. You will not be billed for it"
                        + " from the next period.")));
                return out;
            }).orElse(List.of());
            case "ServiceTerminatedEvent" -> one(resource(event, "service").flatMap(svc ->
                    customer(svc).map(party -> {
                        String reason = String.valueOf(svc.getOrDefault("reason", ""));
                        boolean ported = reason.toLowerCase().contains("port");
                        return new Notification(party,
                                ported ? "Your number has moved" : "Your subscription has ended",
                                (ported
                                    ? "Your number " + svc.getOrDefault("releasedNumber", "")
                                        + " now lives with your new operator. "
                                    : svc.getOrDefault("name", "Your service")
                                        + " is cancelled"
                                        + (svc.get("releasedNumber") != null
                                            ? " and the number " + svc.get("releasedNumber")
                                                + " has been released. " : ". "))
                                + "Your final bill will cover only the days you used."
                                + " Sorry to see you go — the door stays open.");
                    })));
            case "ServiceSuspendedEvent" -> one(resource(event, "service").flatMap(svc ->
                    customer(svc).map(party -> new Notification(party,
                            "Your line is paused",
                            svc.getOrDefault("name", "Your service") + " is paused"
                            + (svc.get("resumeAt") != null
                                ? " until " + String.valueOf(svc.get("resumeAt")).substring(0, 10)
                                    + " — it will resume by itself."
                                : " until you ask us to resume it.")
                            + " Your number and SIM stay yours."))));
            case "ServiceResumedEvent" -> one(resource(event, "service").flatMap(svc ->
                    customer(svc).map(party -> new Notification(party,
                            "Your line is back on",
                            svc.getOrDefault("name", "Your service")
                            + " is active again. Welcome back."))));
            // an unrequested number change is a takeover in progress — say so
            case "NumberChangedEvent" -> one(resource(event, "service").flatMap(svc ->
                    customer(svc).map(party -> new Notification(party,
                            "Your phone number has changed",
                            "Your number is now " + svc.getOrDefault("number", "")
                            + ". The old number " + svc.getOrDefault("oldNumber", "")
                            + " no longer reaches you — tell your contacts."
                            + " If you did not ask for this, contact us immediately."))));
            // and a SWAPPED card even less so — the textbook account-takeover
            case "SimReplacedEvent" -> one(resource(event, "sim").flatMap(sim ->
                    customer(sim).map(party -> new Notification(party,
                            "A new SIM was issued for your number",
                            "Your old card " + sim.getOrDefault("oldIccid", "")
                            + " has stopped working (" + sim.getOrDefault("reason", "replaced")
                            + ") and a new SIM " + sim.getOrDefault("iccid", "") + " is active."
                            + " If this was not you, contact us immediately."))));
            case "ProductOrderStateChangeEvent" -> resource(event, "productOrder")
                    .map(this::orderStateChanged).orElse(List.of());
            // a requested invoice copy: the in-app note says it went out,
            // the EMAIL carries the actual PDF — to the address on file
            case "CustomerBillResendEvent" -> one(resource(event, "billResend").flatMap(r ->
                    customer(r).map(party -> new Notification(party,
                            "Your invoice " + r.getOrDefault("billNo", ""),
                            "As requested, here is a copy of invoice " + r.getOrDefault("billNo", "")
                            + " (amount due " + r.getOrDefault("amountDue", "?") + ")."
                            + " The PDF is attached to the email sent to your address on file.",
                            r.getOrDefault("billNo", "invoice") + ".pdf",
                            r.get("pdfBase64") == null ? null : String.valueOf(r.get("pdfBase64"))))));
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

    private Optional<String> partyWithRole(Map<String, Object> resource, String role) {
        if (resource.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && ref.get("id") != null
                        && role.equalsIgnoreCase(String.valueOf(ref.get("role")))) {
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
    private static String amountOf(Object refund) {
        if (refund instanceof Map<?, ?> r && r.get("amount") instanceof Map<?, ?> a) {
            Object unit = a.get("unit");
            return a.get("value") + " " + (unit == null ? "" : unit);
        }
        return "?";
    }

    private static int asInt(Object v) {
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

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
