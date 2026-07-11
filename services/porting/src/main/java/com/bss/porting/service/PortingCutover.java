package com.bss.porting.service;

import com.bss.porting.entity.PortingOrder;
import com.bss.porting.events.DomainEventPublisher;
import com.bss.porting.repository.PortingOrderRepository;
import com.bss.porting.security.TenantContext;
import com.bss.porting.security.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cutover callback. In production the clearinghouse confirms the port at
 * the agreed window; in dev that window is compressed to a few seconds so the
 * checkout demo flows without a day's wait. Enabled by setting
 * bss.porting.auto-complete-seconds > 0.
 */
@Component
public class PortingCutover {

    private static final Logger log = LoggerFactory.getLogger(PortingCutover.class);

    private final PortingOrderRepository orders;
    private final DomainEventPublisher events;
    private final TenantRegistry tenants;
    private final int afterSeconds;

    public PortingCutover(PortingOrderRepository orders, DomainEventPublisher events,
            TenantRegistry tenants,
            @Value("${bss.porting.auto-complete-seconds:0}") int afterSeconds) {
        this.orders = orders;
        this.events = events;
        this.tenants = tenants;
        this.afterSeconds = afterSeconds;
    }

    @Scheduled(fixedDelayString = "${bss.porting.cutover-poll-ms:2000}")
    public void sweep() {
        if (afterSeconds <= 0) {
            return; // disabled — cutover is triggered explicitly (production / tests)
        }
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (TenantContext ignored = TenantContext.actAs(tenant.getId())) {
                completeDue(tenant.getId());
            } catch (Exception e) {
                log.warn("cutover sweep skipped tenant '{}': {}", tenant.getId(), e.getMessage());
            }
        }
    }

    private void completeDue(String tenant) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(afterSeconds);
        for (PortingOrder o : orders.findByTenantIdOrderByCreatedAtDesc(tenant)) {
            if (PortingOrder.SCHEDULED.equals(o.getStatus()) && o.getCreatedAt().isBefore(cutoff)) {
                o.setStatus(PortingOrder.COMPLETED);
                o.setCompletedAt(OffsetDateTime.now());
                o.setLastUpdate(OffsetDateTime.now());
                orders.save(o);
                Map<String, Object> resource = new LinkedHashMap<>();
                resource.put("id", o.getId());
                resource.put("status", o.getStatus());
                resource.put("phoneNumber", o.getPhoneNumber());
                if (o.getOwnerPartyId() != null) {
                    resource.put("relatedParty", List.of(Map.of("id", o.getOwnerPartyId(), "role", "customer")));
                }
                events.publish("PortingOrderStateChangeEvent", "portingOrder", resource, tenant);
                log.info("cutover: {} completed for {}", o.getPhoneNumber(), o.getOwnerPartyId());
            }
        }
    }
}
