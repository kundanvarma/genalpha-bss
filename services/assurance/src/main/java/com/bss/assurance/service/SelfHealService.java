package com.bss.assurance.service;

import com.bss.assurance.client.SelfHealClients;
import com.bss.assurance.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The autonomy moment of the AI-slice story: a fibre cut hits mid-match
 * and the system works out the fix on its own. Every service riding the
 * failed path is re-homed to the edge site, a ticket documents what
 * happened and why (the ITSM leg), and the caller can then resolve the
 * problem — SLA restored without a human in the loop. Healing is
 * fail-soft: if it cannot fix, the problem simply stays open for people.
 */
@Service
public class SelfHealService {

    private static final Logger log = LoggerFactory.getLogger(SelfHealService.class);
    private static final String FIBRE_PREFIX = "fibre-route-";

    private final SelfHealClients clients;
    private final DomainEventPublisher events;

    public SelfHealService(SelfHealClients clients, DomainEventPublisher events) {
        this.clients = clients;
        this.events = events;
    }

    /** @return number of services re-homed (0 = nothing healable on this object) */
    public int attemptHeal(String affectedObject) {
        if (affectedObject == null || !affectedObject.startsWith(FIBRE_PREFIX)) {
            return 0;
        }
        String site = affectedObject.substring(FIBRE_PREFIX.length());
        String target = "edge:gpu-site-" + site;
        int healed = 0;
        for (Map<String, Object> service : clients.servicesOnPath(affectedObject)) {
            try {
                String serviceId = String.valueOf(service.get("id"));
                clients.migrate(serviceId, target);
                String party = ownerOf(service);
                clients.openTicket(
                        "SLA protected: " + service.get("name") + " re-homed to edge",
                        "Fibre cut on " + affectedObject + " detected by assurance; the slice was"
                                + " automatically migrated to " + target + " and the SLA restored."
                                + " No manual action required — this ticket documents the incident.",
                        party);
                events.publish("ServiceHealedEvent", "serviceHeal", Map.of(
                        "serviceId", serviceId,
                        "serviceName", String.valueOf(service.get("name")),
                        "from", affectedObject,
                        "to", target,
                        "relatedParty", party == null ? List.of()
                                : List.of(Map.of("id", party, "role", "customer"))));
                healed++;
                log.info("self-heal: {} re-homed {} -> {}", service.get("name"), affectedObject, target);
            } catch (Exception e) {
                log.warn("self-heal failed for service {}: {}", service.get("id"), e.getMessage());
            }
        }
        return healed;
    }

    private static String ownerOf(Map<String, Object> service) {
        if (service.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> party && party.get("id") != null) {
            return String.valueOf(party.get("id"));
        }
        return null;
    }
}
