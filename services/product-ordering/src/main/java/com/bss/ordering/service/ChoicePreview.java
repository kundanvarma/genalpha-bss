package com.bss.ordering.service;

import com.bss.ordering.client.CatalogClient;
import com.bss.ordering.client.ChoicesClient;
import com.bss.ordering.client.CollectionsClient;
import com.bss.ordering.client.PolicyClient;
import com.bss.ordering.exception.OrderValidationException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Can I pick this?" (#392) — answered BEFORE an order, from the same checks the
 * order would run, holding nothing:
 * <ol>
 * <li>it is a benefit;</li>
 * <li>the operator's policy allows a new pick for this household (eligibility:
 * the same choicePick / inCollections facts an order hands policy, #391) — first,
 * because an order meets policy before the ledger;</li>
 * <li>this operator offers Choices, enough are left, and it is not already
 * picked — the ledger's own refusals, in the ledger's own words and sequence.</li>
 * </ol>
 * The first "no" is the answer, in words. Risk scoring and the credit bureau are
 * NOT consulted — each records an assessment when asked, and a preview must leave
 * no trace — so a rule built on riskScore or creditDecision can still refuse at
 * order time. That is the one way a preview's yes can become an order's no.
 */
@Service
public class ChoicePreview {

    public record Answer(boolean allowed, String reason, String offeringName, int cost, int left, String label) {
    }

    private final CatalogClient catalog;
    private final ChoicesClient ledger;
    private final CollectionsClient collections;
    private final PolicyClient policy;

    public ChoicePreview(CatalogClient catalog, ChoicesClient ledger, CollectionsClient collections,
            PolicyClient policy) {
        this.catalog = catalog;
        this.ledger = ledger;
        this.collections = collections;
        this.policy = policy;
    }

    public Answer preview(String ownerPartyId, String offeringId) {
        Map<String, Object> detail = catalog.findOfferingDetail(offeringId)
                .orElseThrow(() -> new OrderValidationException("no offering " + offeringId));
        String name = detail.get("name") == null ? offeringId : detail.get("name").toString();
        int cost = detail.get("choiceCost") instanceof Number n ? n.intValue() : 0;
        if (cost <= 0) {
            throw new OrderValidationException(name + " is not a benefit — it costs no Choices");
        }
        ChoicesClient.Standing standing = ledger.standing(ownerPartyId);
        String label = standing.label();
        // the ORDER's own sequence: policy first (enforcePolicy), then the ledger's hold —
        // so the first "no" here is the first "no" an order would meet
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("party", ownerPartyId);
        context.put("items", List.of(Map.of("offeringId", offeringId, "name", name, "quantity", 1)));
        context.put("offeringIds", List.of(offeringId));
        context.put("quantityByOffering", Map.of(offeringId, 1));
        context.put("maxLineQuantity", 1);
        context.put("totalQuantity", 1);
        context.put("lineCount", 1);
        context.put("choicePick", true);
        collections.inCollections(ownerPartyId).ifPresent(v -> context.put("inCollections", v));
        PolicyClient.Decision decision = policy.evaluateOrder(context);
        if (decision != null && !decision.allowed()) {
            return new Answer(false, decision.message(), name, cost, standing.left(), label);
        }
        if (!standing.offered()) {
            return new Answer(false, label + " are not offered here", name, cost, 0, label);
        }
        if (standing.left() < cost) {
            return new Answer(false, name + " costs " + cost + " of your " + label + " and you have "
                    + standing.left() + " left", name, cost, standing.left(), label);
        }
        if (standing.pickedOfferingIds().contains(offeringId)) {
            return new Answer(false, name + " is already picked", name, cost, standing.left(), label);
        }
        return new Answer(true, null, name, cost, standing.left(), label);
    }
}
