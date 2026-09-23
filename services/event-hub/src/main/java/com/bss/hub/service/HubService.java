package com.bss.hub.service;

import com.bss.hub.api.DeliveryView;
import com.bss.hub.api.HubRequest;
import com.bss.hub.api.HubView;
import com.bss.hub.api.Json;
import com.bss.hub.entity.HubDelivery;
import com.bss.hub.entity.HubSubscription;
import com.bss.hub.exception.BadRequestException;
import com.bss.hub.exception.NotFoundException;
import com.bss.hub.repository.HubDeliveryRepository;
import com.bss.hub.repository.HubSubscriptionRepository;
import com.bss.hub.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF688, the subscription half: register a callback and a filter, and
 * the fleet's envelopes come to you — tenant-walled, ledgered, retried.
 * Registration is partner/staff-grade: an event feed is a firehose of
 * business facts, never a customer surface.
 */
@Service
public class HubService {

    private static final Logger log = LoggerFactory.getLogger(HubService.class);

    private final HubSubscriptionRepository subscriptions;
    private final HubDeliveryRepository deliveries;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HubService(HubSubscriptionRepository subscriptions, HubDeliveryRepository deliveries,
            TenantScope tenantScope) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public HubView register(HubRequest dto) {
        String callback = dto.callbackText();
        if (callback == null || !callback.startsWith("http")) {
            throw new BadRequestException("callback (http/https URL) is required");
        }
        HubSubscription sub = new HubSubscription();
        sub.setId(UUID.randomUUID().toString());
        sub.setTenantId(tenantScope.currentTenantId());
        sub.setCallback(callback);
        JsonNode types = dto.eventTypesOrNull();
        if (types != null) {
            sub.setEventTypesJson(writeJson(types));
        }
        sub.setActive(true);
        sub.setCreatedAt(OffsetDateTime.now());
        subscriptions.save(sub);
        log.info("hub: listener {} registered for {} ({})", sub.getId(),
                sub.getEventTypesJson() == null ? "ALL events" : sub.getEventTypesJson(), callback);
        return view(sub);
    }

    @Transactional
    public void unregister(String id) {
        HubSubscription sub = subscriptions.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("HubSubscription", id));
        sub.setActive(false);
        subscriptions.save(sub);
    }

    @Transactional(readOnly = true)
    public List<HubView> list() {
        return subscriptions.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<DeliveryView> deliveriesOf(String subscriptionId) {
        return deliveries.findTop100ByTenantIdAndSubscriptionIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId(), subscriptionId)
                .stream().map(d -> DeliveryView.of(d.getId(), d.getEventType(), d.getStatus(),
                        d.getAttempts(), d.getLastError(), d.getCreatedAt()))
                .toList();
    }

    /** Ingestion (listener-called, acting as the EVENT's tenant): fan out
     * one delivery row per matching subscription — the ledger is the queue. */
    @Transactional
    public void onFleetEvent(String tenantId, String eventType, String payload) {
        for (HubSubscription sub : subscriptions.findByTenantIdAndActiveTrue(tenantId)) {
            if (!matches(sub, eventType)) {
                continue;
            }
            HubDelivery d = new HubDelivery();
            d.setId(UUID.randomUUID().toString());
            d.setTenantId(tenantId);
            d.setSubscriptionId(sub.getId());
            d.setEventType(eventType);
            d.setPayload(payload.length() > 7900 ? payload.substring(0, 7900) : payload);
            d.setStatus(HubDelivery.PENDING);
            d.setAttempts(0);
            d.setNextAttemptAt(OffsetDateTime.now());
            d.setCreatedAt(OffsetDateTime.now());
            deliveries.save(d);
        }
    }

    private boolean matches(HubSubscription sub, String eventType) {
        if (sub.getEventTypesJson() == null) {
            return true;
        }
        try {
            List<?> types = objectMapper.readValue(sub.getEventTypesJson(), List.class);
            return types.stream().anyMatch(t -> String.valueOf(t).equals(eventType));
        } catch (Exception e) {
            return false;
        }
    }

    private HubView view(HubSubscription sub) {
        return HubView.of(sub.getId(), sub.getCallback(), storedFilter(sub.getEventTypesJson()),
                sub.isActive(), sub.getCreatedAt());
    }

    /**
     * The stored filter as the partner posted it. The map path read the
     * column with {@code readValue(json, List.class)}: a JSON array came
     * back as a list, a literal {@code null} came back as a written null,
     * and anything else threw and left the key OFF. A tree reproduces all
     * three — nothing else may be admitted, or an unreadable column would
     * start answering where it used to stay silent.
     */
    private JsonNode storedFilter(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && (node.isArray() || node.isNull()) ? node : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
