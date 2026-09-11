package com.bss.som.listen;

import com.bss.som.client.SimPlatformClient;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.SimCard;
import com.bss.som.events.DomainEventPublisher;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.repository.SimCardRepository;
import com.bss.som.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone moved its subscription to a new eSIM through the entitlement
 * server (GSMA TS.43 ODSA, primary transfer): the line-side truth follows —
 * the old card is blocked at the SIM platform, the new profile (its ICCID,
 * EID) becomes the line's active SIM, and {@code SimReplacedEvent} tells the
 * rest of the BSS, the entitlement server included.
 */
@Component
@ConditionalOnProperty(name = "bss.events.enabled", havingValue = "true", matchIfMissing = true)
public class EntitlementEventListener {

    private static final Logger log = LoggerFactory.getLogger(EntitlementEventListener.class);

    private final ServiceInstanceRepository services;
    private final SimCardRepository sims;
    private final SimPlatformClient simPlatform;
    private final DomainEventPublisher events;
    private final ObjectMapper objectMapper;

    public EntitlementEventListener(ServiceInstanceRepository services, SimCardRepository sims,
            SimPlatformClient simPlatform, DomainEventPublisher events, ObjectMapper objectMapper) {
        this.services = services;
        this.sims = sims;
        this.simPlatform = simPlatform;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${bss.events.entitlement-topic:bss.entitlement.events}", groupId = "som-entitlement",
            autoStartup = "${bss.events.enabled:true}")
    public void onEntitlementEvent(String message) {
        try {
            Map<?, ?> envelope = objectMapper.readValue(message, Map.class);
            if (!"SubscriptionTransferRequestedEvent".equals(envelope.get("eventType"))) {
                return;
            }
            String tenantId = envelope.get("tenantId") == null ? null : String.valueOf(envelope.get("tenantId"));
            Object payload = envelope.get("event");
            if (tenantId == null || !(payload instanceof Map<?, ?> p) || !(p.get("subscriptionTransfer") instanceof Map<?, ?> t)) {
                return;
            }
            String serviceId = t.get("serviceId") == null ? null : String.valueOf(t.get("serviceId"));
            String newIccid = t.get("newIccid") == null ? null : String.valueOf(t.get("newIccid"));
            String eid = t.get("targetEid") == null ? null : String.valueOf(t.get("targetEid"));
            if (serviceId == null || newIccid == null) {
                return;
            }
            try (TenantContext ignored = TenantContext.actAs(tenantId)) {
                transfer(tenantId, serviceId, newIccid, eid, String.valueOf(t.get("id")));
            }
        } catch (Exception e) {
            log.warn("eSIM transfer not applied ({})", e.getMessage());
        }
    }

    @Transactional
    void transfer(String tenantId, String serviceId, String newIccid, String eid, String transferId) {
        ServiceInstance line = services.findByIdAndTenantId(serviceId, tenantId).orElse(null);
        if (line == null) {
            log.warn("eSIM transfer {}: no line {} in tenant {}", transferId, serviceId, tenantId);
            return;
        }
        SimCard old = sims.findFirstByTenantIdAndServiceIdAndStatus(tenantId, serviceId, "active").orElse(null);
        if (old != null) {
            if (!simPlatform.block(old.getIccid())) {
                log.warn("eSIM transfer {}: the SIM platform refused to block {} — transfer left pending", transferId, old.getIccid());
                return;
            }
            old.setStatus("replaced");
            old.setReplacedReason("esim-transfer");
            old.setLastUpdate(OffsetDateTime.now());
            sims.save(old);
        }
        SimCard fresh = new SimCard();
        fresh.setIccid(newIccid);
        fresh.setTenantId(tenantId);
        fresh.setServiceId(serviceId);
        fresh.setForm("esim");
        fresh.setEid(eid);
        fresh.setPuk(""); // an eSIM profile carries no PUK the BSS reveals
        fresh.setCreatedAt(OffsetDateTime.now());
        fresh.setLastUpdate(OffsetDateTime.now());
        sims.save(fresh);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("serviceId", serviceId);
        event.put("reason", "esim-transfer");
        event.put("transferId", transferId);
        event.put("form", "esim");
        if (old != null) {
            event.put("oldIccid", "•••• " + old.getIccid().substring(Math.max(0, old.getIccid().length() - 5)));
        }
        event.put("iccid", "•••• " + newIccid.substring(Math.max(0, newIccid.length() - 5)));
        if (line.getOwnerPartyId() != null) {
            event.put("relatedParty", List.of(Map.of("id", line.getOwnerPartyId(), "role", "customer")));
        }
        events.publish("SimReplacedEvent", "sim", event, tenantId);
        log.info("eSIM transfer {}: line {} now on eSIM profile ending {}", transferId, serviceId,
                newIccid.substring(Math.max(0, newIccid.length() - 5)));
    }
}
