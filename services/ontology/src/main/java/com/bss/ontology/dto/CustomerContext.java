package com.bss.ontology.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * A customer's sub-graph for an agent: the person, their subscriptions with
 * what each could become, their lines, their bills, the receipts of what the
 * BSS decided about them — walked with the caller's rights. {@code unanswered}
 * names every edge that did not answer.
 */
@JsonPropertyOrder({"customerId", "customer", "subscriptions", "services", "bills", "receipts", "actionsAvailable", "unanswered", "@type"})
public record CustomerContext(String customerId, @JsonInclude(JsonInclude.Include.NON_NULL) Customer customer,
        List<Subscription> subscriptions, List<ServiceLine> services, List<Bill> bills, List<Receipt> receipts,
        List<String> actionsAvailable, List<String> unanswered, @JsonProperty("@type") String type) {

    @JsonPropertyOrder({"id", "name", "status"})
    public record Customer(String id, String name, String status) {
    }

    /** A product of the inventory; {@code availableUpgrades} only while it is active and the shelf answered. */
    @JsonPropertyOrder({"id", "name", "status", "offeringId", "since", "previousOffering", "offeringChangedAt", "availableUpgrades"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subscription(String id, String name, String status, String offeringId, String since, String previousOffering,
            String offeringChangedAt, List<UpgradeOption> availableUpgrades) {
    }

    /** A service of the inventory, named by its number when it has one. */
    @JsonPropertyOrder({"id", "name", "state", "number"})
    public record ServiceLine(String id, String name, String state, String number) {
    }

    @JsonPropertyOrder({"id", "state", "billDate", "amountDue"})
    public record Bill(String id, String state, String billDate, String amountDue) {
    }

    /** A decision of the BSS about one of the customer's subscriptions, from the decision log. */
    @JsonPropertyOrder({"decisionId", "decisionPoint", "action", "reason", "decidedAt", "outcome"})
    public record Receipt(String decisionId, String decisionPoint, String action, String reason, String decidedAt, String outcome) {
    }
}
