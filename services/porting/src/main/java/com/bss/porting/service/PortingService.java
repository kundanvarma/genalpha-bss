package com.bss.porting.service;

import com.bss.porting.api.ApiConstants;
import com.bss.porting.dto.EntityRef;
import com.bss.porting.dto.PartyRef;
import com.bss.porting.dto.PortedNumber;
import com.bss.porting.dto.PortingOrderRequest;
import com.bss.porting.dto.PortingOrderView;
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
    public PortingOrderView create(PortingOrderRequest dto) {
        String direction = dto.direction() == null ? PortingOrder.PORT_IN : dto.direction();
        if (dto.phoneNumber() == null || dto.country() == null) {
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
        order.setPhoneNumber(dto.phoneNumber().replaceAll("\\s", ""));
        order.setCountry(dto.country().toUpperCase());
        order.setOtherOperator(dto.otherOperator());
        if (dto.firstPartyId() != null) {
            order.setOwnerPartyId(dto.firstPartyId());
        }
        // A customer can only port their own number, whatever they send.
        partyScope.scopedPartyId().ifPresent(order::setOwnerPartyId);
        order.setProductOrderId(dto.productOrderId());
        order.setGateway(gateway.nameFor(order.getTenantId(), order.getCountry()));
        order.setStatus(PortingOrder.REQUESTED);
        order.setRequestedCutover(parseTime(dto.requestedCutover()));
        // The customer's port-in wish date: honest window, never the past.
        if (order.getRequestedCutover() != null
                && order.getRequestedCutover().isBefore(OffsetDateTime.now().minusHours(1))) {
            throw new BadRequestException("requestedCutover cannot be in the past");
        }
        order.setCreatedAt(OffsetDateTime.now());
        order.setLastUpdate(OffsetDateTime.now());

        // Validate through the clearinghouse and, if accepted, schedule cutover.
        PortingGateway.Decision decision = gateway.validate(new PortingGateway.PortingRequest(
                direction, order.getPhoneNumber(), order.getCountry(),
                order.getOtherOperator(), order.getOwnerPartyId(), order.getTenantId()));
        if (!decision.accepted()) {
            order.setStatus(PortingOrder.REJECTED);
            order.setRejectReason(decision.rejectReason());
        } else {
            order.setStatus(PortingOrder.SCHEDULED);
            order.setScheduledCutover(parseTime(decision.scheduledCutoverIso()));
            // The customer's wish date WINS when it is later than the
            // clearinghouse's earliest window — production agrees a slot on or
            // after the wish, never before it.
            if (order.getRequestedCutover() != null
                    && (order.getScheduledCutover() == null
                        || order.getRequestedCutover().isAfter(order.getScheduledCutover()))) {
                order.setScheduledCutover(order.getRequestedCutover());
            }
        }
        orders.save(order);
        PortingOrderView result = toView(order);
        events.publish("PortingOrderCreateEvent", "portingOrder", result);
        log.info("porting {} {} via {} -> {}", direction, order.getPhoneNumber(),
                gateway.nameFor(order.getTenantId(), order.getCountry()), order.getStatus());
        return result;
    }

    /** The cutover fires (in production, a clearinghouse callback at the agreed time). */
    @Transactional
    public PortingOrderView complete(String id) {
        PortingOrder order = own(id);
        if (!PortingOrder.SCHEDULED.equals(order.getStatus())) {
            throw new ConflictException("only scheduled ports can complete (is " + order.getStatus() + ")");
        }
        boolean ok = gateway.confirmCutover(new PortingGateway.PortingRequest(
                order.getDirection(), order.getPhoneNumber(), order.getCountry(),
                order.getOtherOperator(), order.getOwnerPartyId(), order.getTenantId()));
        if (!ok) {
            throw new ConflictException("the clearinghouse did not confirm the cutover");
        }
        order.setStatus(PortingOrder.COMPLETED);
        order.setCompletedAt(OffsetDateTime.now());
        order.setLastUpdate(OffsetDateTime.now());
        orders.save(order);
        PortingOrderView result = toView(order);
        events.publish("PortingOrderStateChangeEvent", "portingOrder", result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<PortingOrderView> findAll(String relatedPartyId, String status) {
        String scoped = partyScope.scopedPartyId().orElse(relatedPartyId);
        return orders.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .filter(o -> scoped == null || scoped.equals(o.getOwnerPartyId()))
                .filter(o -> status == null || status.equals(o.getStatus()))
                .map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public PortingOrderView findById(String id) {
        return toView(own(id));
    }

    /** The number a party has successfully ported in, for the orchestrator to activate on. */
    @Transactional(readOnly = true)
    public PortedNumber portedNumberFor(String party) {
        return orders.findByTenantIdAndOwnerPartyIdAndStatus(
                        tenantScope.currentTenantId(), party, PortingOrder.COMPLETED).stream()
                .filter(o -> PortingOrder.PORT_IN.equals(o.getDirection()))
                .findFirst()
                .map(o -> new PortedNumber(o.getPhoneNumber(), o.getId()))
                .orElse(PortedNumber.NONE);
    }

    private PortingOrder own(String id) {
        return orders.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("PortingOrder", id));
    }

    private PortingOrderView toView(PortingOrder o) {
        return new PortingOrderView(o.getId(), o.getHref(), o.getDirection(), o.getPhoneNumber(),
                o.getCountry(), o.getOtherOperator(), o.getStatus(), o.getGateway(),
                PortingRules.forCountry(o.getCountry()).regulator(), o.getRejectReason(),
                text(o.getRequestedCutover()), text(o.getScheduledCutover()),
                text(o.getCompletedAt()),
                o.getProductOrderId() == null ? null : new EntityRef(o.getProductOrderId()),
                o.getOwnerPartyId() == null ? null
                        : List.of(PartyRef.customer(o.getOwnerPartyId())),
                "PortingOrder");
    }

    private static String text(OffsetDateTime value) {
        return value == null ? null : value.toString();
    }

    private static OffsetDateTime parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
