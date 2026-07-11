package com.bss.porting.service;

import com.bss.porting.api.ApiConstants;
import com.bss.porting.entity.PortingOrder;
import com.bss.porting.events.DomainEventPublisher;
import com.bss.porting.exception.BadRequestException;
import com.bss.porting.exception.ConflictException;
import com.bss.porting.exception.NotFoundException;
import com.bss.porting.gateway.PortingGateway;
import com.bss.porting.gateway.PortingRules;
import com.bss.porting.repository.PortingOrderRepository;
import com.bss.porting.security.TenantScope;
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
 * Number portability, BSS-side. A port-in captures the customer's existing
 * number and losing operator, validates it through the country's clearinghouse
 * (NRDB in Norway) and schedules a cutover; completing the cutover makes the
 * number the customer's — the orchestrator then activates the service on the
 * PORTED number instead of drawing a fresh one from the pool. The regime is
 * pluggable and country-aware, so "keep your number" means the right thing in
 * every market the operator runs in.
 */
@Service
public class PortingService {

    private static final Logger log = LoggerFactory.getLogger(PortingService.class);

    private final PortingOrderRepository orders;
    private final PortingGateway gateway;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final com.bss.porting.security.PartyScope partyScope;

    public PortingService(PortingOrderRepository orders, PortingGateway gateway,
            DomainEventPublisher events, TenantScope tenantScope,
            com.bss.porting.security.PartyScope partyScope) {
        this.orders = orders;
        this.gateway = gateway;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        String direction = dto.get("direction") == null ? PortingOrder.PORT_IN
                : String.valueOf(dto.get("direction"));
        if (dto.get("phoneNumber") == null || dto.get("country") == null) {
            throw new BadRequestException("phoneNumber and country (ISO alpha-2) are required");
        }
        if (!List.of(PortingOrder.PORT_IN, PortingOrder.PORT_OUT).contains(direction)) {
            throw new BadRequestException("direction must be portIn or portOut");
        }
        PortingOrder order = new PortingOrder();
        order.setId(UUID.randomUUID().toString());
        order.setTenantId(tenantScope.currentTenantId());
        order.setHref(ApiConstants.BASE_PATH + "/numberPortingOrder/" + order.getId());
        order.setDirection(direction);
        order.setPhoneNumber(String.valueOf(dto.get("phoneNumber")).replaceAll("\\s", ""));
        order.setCountry(String.valueOf(dto.get("country")).toUpperCase());
        order.setOtherOperator(dto.get("otherOperator") == null ? null
                : String.valueOf(dto.get("otherOperator")));
        if (dto.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> party && party.get("id") != null) {
            order.setOwnerPartyId(String.valueOf(party.get("id")));
        }
        // A customer can only port their own number, whatever they send.
        partyScope.scopedPartyId().ifPresent(order::setOwnerPartyId);
        order.setProductOrderId(dto.get("productOrderId") == null ? null
                : String.valueOf(dto.get("productOrderId")));
        order.setGateway(gateway.name());
        order.setStatus(PortingOrder.REQUESTED);
        order.setRequestedCutover(parseTime(dto.get("requestedCutover")));
        order.setCreatedAt(OffsetDateTime.now());
        order.setLastUpdate(OffsetDateTime.now());

        // Validate through the clearinghouse and, if accepted, schedule cutover.
        PortingGateway.Decision decision = gateway.validate(new PortingGateway.PortingRequest(
                direction, order.getPhoneNumber(), order.getCountry(),
                order.getOtherOperator(), order.getOwnerPartyId()));
        if (!decision.accepted()) {
            order.setStatus(PortingOrder.REJECTED);
            order.setRejectReason(decision.rejectReason());
        } else {
            order.setStatus(PortingOrder.SCHEDULED);
            order.setScheduledCutover(parseTime(decision.scheduledCutoverIso()));
        }
        orders.save(order);
        Map<String, Object> result = toMap(order);
        events.publish("PortingOrderCreateEvent", "portingOrder", result);
        log.info("porting {} {} via {} -> {}", direction, order.getPhoneNumber(),
                gateway.name(), order.getStatus());
        return result;
    }

    /** The cutover fires (in production, a clearinghouse callback at the agreed time). */
    @Transactional
    public Map<String, Object> complete(String id) {
        PortingOrder order = own(id);
        if (!PortingOrder.SCHEDULED.equals(order.getStatus())) {
            throw new ConflictException("only scheduled ports can complete (is " + order.getStatus() + ")");
        }
        boolean ok = gateway.confirmCutover(new PortingGateway.PortingRequest(
                order.getDirection(), order.getPhoneNumber(), order.getCountry(),
                order.getOtherOperator(), order.getOwnerPartyId()));
        if (!ok) {
            throw new ConflictException("the clearinghouse did not confirm the cutover");
        }
        order.setStatus(PortingOrder.COMPLETED);
        order.setCompletedAt(OffsetDateTime.now());
        order.setLastUpdate(OffsetDateTime.now());
        orders.save(order);
        Map<String, Object> result = toMap(order);
        events.publish("PortingOrderStateChangeEvent", "portingOrder", result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll(String relatedPartyId, String status) {
        String scoped = partyScope.scopedPartyId().orElse(relatedPartyId);
        return orders.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .filter(o -> scoped == null || scoped.equals(o.getOwnerPartyId()))
                .filter(o -> status == null || status.equals(o.getStatus()))
                .map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        return toMap(own(id));
    }

    /** The number a party has successfully ported in, for the orchestrator to activate on. */
    @Transactional(readOnly = true)
    public Map<String, Object> portedNumberFor(String party) {
        return orders.findByTenantIdAndOwnerPartyIdAndStatus(
                        tenantScope.currentTenantId(), party, PortingOrder.COMPLETED).stream()
                .filter(o -> PortingOrder.PORT_IN.equals(o.getDirection()))
                .findFirst()
                .map(o -> Map.<String, Object>of("phoneNumber", o.getPhoneNumber(),
                        "portingOrderId", o.getId()))
                .orElse(Map.of());
    }

    private PortingOrder own(String id) {
        return orders.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("PortingOrder", id));
    }

    private Map<String, Object> toMap(PortingOrder o) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("href", o.getHref());
        map.put("direction", o.getDirection());
        map.put("phoneNumber", o.getPhoneNumber());
        map.put("country", o.getCountry());
        if (o.getOtherOperator() != null) map.put("otherOperator", o.getOtherOperator());
        map.put("status", o.getStatus());
        map.put("clearinghouse", o.getGateway());
        map.put("regulator", PortingRules.forCountry(o.getCountry()).regulator());
        if (o.getRejectReason() != null) map.put("rejectReason", o.getRejectReason());
        if (o.getScheduledCutover() != null) map.put("scheduledCutover", o.getScheduledCutover().toString());
        if (o.getCompletedAt() != null) map.put("completedAt", o.getCompletedAt().toString());
        if (o.getProductOrderId() != null) map.put("productOrder", Map.of("id", o.getProductOrderId()));
        if (o.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", o.getOwnerPartyId(), "role", "customer")));
        }
        map.put("@type", "PortingOrder");
        return map;
    }

    private static OffsetDateTime parseTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}
