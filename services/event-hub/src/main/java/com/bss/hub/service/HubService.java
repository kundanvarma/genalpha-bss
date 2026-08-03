package com.bss.hub.service;

import com.bss.hub.entity.HubDelivery;
import com.bss.hub.entity.HubSubscription;
import com.bss.hub.exception.BadRequestException;
import com.bss.hub.exception.NotFoundException;
import com.bss.hub.repository.HubDeliveryRepository;
import com.bss.hub.repository.HubSubscriptionRepository;
import com.bss.hub.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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
    public Map<String, Object> register(Map<String, Object> dto) {
        String callback = dto.get("callback") == null ? null : String.valueOf(dto.get("callback"));
        if (callback == null || !callback.startsWith("http")) {
            throw new BadRequestException("callback (http/https URL) is required");
        }
        HubSubscription sub = new HubSubscription();
        sub.setId(UUID.randomUUID().toString());
        sub.setTenantId(tenantScope.currentTenantId());
        sub.setCallback(callback);
        if (dto.get("eventTypes") instanceof List<?> types && !types.isEmpty()) {
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
    public List<Map<String, Object>> list() {
        return subscriptions.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> deliveriesOf(String subscriptionId) {
        return deliveries.findTop100ByTenantIdAndSubscriptionIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId(), subscriptionId)
                .stream().map(d -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", d.getId());
                    map.put("eventType", d.getEventType());
                    map.put("status", d.getStatus());
                    map.put("attempts", d.getAttempts());
                    if (d.getLastError() != null) {
                        map.put("lastError", d.getLastError());
                    }
                    map.put("createdAt", d.getCreatedAt());
                    map.put("@type", "HubDelivery");
                    return map;
                }).toList();
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

    private Map<String, Object> view(HubSubscription sub) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", sub.getId());
        map.put("callback", sub.getCallback());
        if (sub.getEventTypesJson() != null) {
            try {
                map.put("eventTypes", objectMapper.readValue(sub.getEventTypesJson(), List.class));
            } catch (Exception ignored) { }
        }
        map.put("active", sub.isActive());
        map.put("createdAt", sub.getCreatedAt());
        map.put("@type", "Hub");
        return map;
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
