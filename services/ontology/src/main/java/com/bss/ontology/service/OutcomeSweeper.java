package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.client.OntologyProperties;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.security.TenantContext;
import com.bss.ontology.security.TenantRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The outcome an action declares it may be measured by, measured. For
 * upgradeSubscription: "the line still active on the new plan N days later".
 * Every sweep reads this tenant's receipts for ontology.* decision points from
 * the decision log (machine identity, insight:read), and for those old enough
 * and not yet judged, reads the subscription (inventory:read) and writes the
 * outcome: retained (still active on the chosen offering), changed (active on
 * another), or lost (no longer active). The window is configurable; the demo
 * compresses it so the loop can be seen closing.
 */
@Component
public class OutcomeSweeper {

    private static final Logger log = LoggerFactory.getLogger(OutcomeSweeper.class);

    private final Registry registry;
    private final ComponentClient client;
    private final ReceiptPublisher receipts;
    private final TenantRegistry tenants;
    private final OntologyProperties props;

    public OutcomeSweeper(Registry registry, ComponentClient client, ReceiptPublisher receipts, TenantRegistry tenants,
            OntologyProperties props) {
        this.registry = registry;
        this.client = client;
        this.receipts = receipts;
        this.tenants = tenants;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${ontology.outcome-sweep-ms:300000}", initialDelayString = "${ontology.outcome-sweep-initial-ms:60000}")
    public void sweep() {
        for (TenantRegistry.TenantEntry t : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(t.getId())) {
                sweepTenant(t.getId());
            } catch (RuntimeException e) {
                log.warn("outcome sweep for {} failed: {}", t.getId(), e.getMessage());
            }
        }
    }

    public int sweepTenant(String tenant) {
        Registry.Layer l = registry.forTenant(tenant);
        JsonNode read = l.capabilities().get("decisionLog.read");
        JsonNode product = l.capabilities().get("productInventory.product");
        if (read == null || product == null) {
            return 0;
        }
        int judged = 0;
        for (JsonNode action : l.actions().values()) {
            if (!action.path("outcome").has("measured")) {
                continue;
            }
            String point = "ontology." + action.path("action").asText();
            ComponentClient.Reply reply = client.callAsMachine(read.path("component").asText(), "GET", read.path("route").path("path").asText(),
                    Map.of("decisionPoint", point, "limit", "200"), null, Map.of());
            if (!reply.ok() || !reply.body().isArray()) {
                continue;
            }
            OffsetDateTime cutoff = OffsetDateTime.now().minusDays(props.getOutcomeAfterDays());
            for (JsonNode d : reply.body()) {
                String outcome = d.path("outcome").asText("");
                if (!outcome.isEmpty() && !"completed".equals(outcome)) {
                    continue; // already judged
                }
                if ("refused".equals(d.path("action").asText()) || d.path("action").asText().isEmpty()) {
                    continue;
                }
                String decidedAt = d.path("decidedAt").asText("");
                if (decidedAt.isEmpty()) {
                    continue;
                }
                try {
                    if (OffsetDateTime.parse(decidedAt).isAfter(cutoff)) {
                        continue;
                    }
                } catch (Exception badDate) {
                    continue;
                }
                String subscriptionId = d.path("subjectId").asText();
                ComponentClient.Reply sub = client.callAsMachine(product.path("component").asText(), "GET",
                        product.path("route").path("path").asText().replace("{id}", subscriptionId), Map.of(), null, Map.of());
                String verdict;
                if (!sub.ok()) {
                    verdict = "lost";
                } else if (!"active".equalsIgnoreCase(sub.body().path("status").asText())) {
                    verdict = "lost";
                } else if (d.path("action").asText().equals(sub.body().path("productOffering").path("id").asText())) {
                    verdict = "retained";
                } else {
                    verdict = "changed";
                }
                receipts.outcome(tenant, d.path("decisionId").asText(), verdict, props.getOutcomeAfterDays());
                judged++;
            }
        }
        if (judged > 0) {
            log.info("outcome sweep {}: {} receipt(s) judged after {} day(s)", tenant, judged, props.getOutcomeAfterDays());
        }
        return judged;
    }
}
