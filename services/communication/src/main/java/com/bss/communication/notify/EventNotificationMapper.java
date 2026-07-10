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
    public Optional<Notification> map(String eventType, Map<String, Object> event) {
        if (event == null) {
            return Optional.empty();
        }
        return switch (eventType) {
            case "ProductOrderCreateEvent" -> resource(event, "productOrder").flatMap(order ->
                    customer(order).map(party -> new Notification(party,
                            "Order received",
                            "We got your order: " + order.getOrDefault("description", "your order")
                                    + ". We'll tell you the moment it's ready.")));
            case "ProductOrderStateChangeEvent" -> resource(event, "productOrder").flatMap(order ->
                    "completed".equals(order.get("state"))
                            ? customer(order).map(party -> new Notification(party,
                                    "Order complete",
                                    "Done! " + order.getOrDefault("description", "Your order")
                                            + " is active. Enjoy."))
                            : Optional.empty());
            case "CustomerBillCreateEvent" -> resource(event, "customerBill").flatMap(bill ->
                    customer(bill).map(party -> new Notification(party,
                            "Your bill is ready",
                            "Bill " + bill.getOrDefault("billNo", "") + " is ready: "
                                    + money(bill.get("amountDue")) + ". Pay it under My bills.")));
            case "TroubleTicketStateChangeEvent" -> resource(event, "troubleTicket").flatMap(ticket ->
                    "resolved".equals(ticket.get("status"))
                            ? customer(ticket).map(party -> new Notification(party,
                                    "Ticket resolved",
                                    "Good news — \"" + ticket.getOrDefault("name", "your ticket")
                                            + "\" is resolved. Close it under Support if all is well."))
                            : Optional.empty());
            case "AppointmentCreateEvent" -> resource(event, "appointment").flatMap(appt ->
                    customer(appt).map(party -> new Notification(party,
                            "Installer booked",
                            "A technician is booked for "
                                    + validForStart(appt) + ". We'll ring the bell.")));
            default -> Optional.empty();
        };
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

    private String validForStart(Map<String, Object> appt) {
        if (appt.get("validFor") instanceof Map<?, ?> v && v.get("startDateTime") != null) {
            return String.valueOf(v.get("startDateTime"));
        }
        return "the agreed time";
    }
}
