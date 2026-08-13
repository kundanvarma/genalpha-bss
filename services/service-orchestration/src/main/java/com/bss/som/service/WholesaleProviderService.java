package com.bss.som.service;

import com.bss.som.entity.ProviderAccessOrder;
import com.bss.som.events.DomainEventPublisher;
import com.bss.som.repository.ProviderAccessOrderRepository;
import com.bss.som.security.TenantContext;
import com.bss.som.security.TenantRegistry;
import com.bss.som.security.TenantScope;
import com.bss.som.tick.TickGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The PROVIDER face of open access: as the fibre owner, we accept access-seeker
 * orders from retailers over our MEF LSO Sonata face, provision them on our own
 * clock, and notify the retailer's callback when the line is live. The mirror of
 * the seeker flow (provisionWholesaleAccess/activateWholesaleAccess), one tenant
 * over: the retailer's SOM is the seeker, ours is the provider.
 */
@Service
public class WholesaleProviderService {

    private static final Logger log = LoggerFactory.getLogger(WholesaleProviderService.class);

    private final ProviderAccessOrderRepository orders;
    private final TenantScope tenantScope;
    private final TenantRegistry tenants;
    private final TickGuard tickGuard;
    private final DomainEventPublisher events;
    private final RestClient.Builder restBuilder;
    private final long activateMs;

    public WholesaleProviderService(ProviderAccessOrderRepository orders, TenantScope tenantScope,
            TenantRegistry tenants, TickGuard tickGuard, DomainEventPublisher events,
            RestClient.Builder restBuilder,
            @Value("${bss.wholesale.provider-activate-ms:5000}") long activateMs) {
        this.orders = orders;
        this.tenantScope = tenantScope;
        this.tenants = tenants;
        this.tickGuard = tickGuard;
        this.events = events;
        this.restBuilder = restBuilder;
        this.activateMs = activateMs;
    }

    /** Accept a retailer's Sonata access order — record it, schedule activation. */
    @Transactional
    public ProviderAccessOrder acceptOrder(String buyerRef, String callbackUrl, String retailerPartyId,
            String accessLayer, Integer bandwidthMbps, String postCode) {
        String tenant = tenantScope.currentTenantId();
        ProviderAccessOrder o = new ProviderAccessOrder();
        o.setId(UUID.randomUUID().toString());
        o.setTenantId(tenant);
        o.setBuyerRef(buyerRef);
        o.setCallbackUrl(callbackUrl);
        o.setRetailerPartyId(retailerPartyId);
        o.setAccessLayer(accessLayer);
        o.setBandwidthMbps(bandwidthMbps);
        o.setPostCode(postCode);
        o.setState(ProviderAccessOrder.ACKNOWLEDGED);
        o.setCreatedAt(OffsetDateTime.now());
        o.setActivateAt(OffsetDateTime.now().plus(Duration.ofMillis(activateMs)));
        o.setLastUpdate(OffsetDateTime.now());
        orders.save(o);
        log.info("provider: accepted access-seeker order {} (buyerRef {}, {} {} Mbit/s at {}) — activating in {}ms",
                o.getId(), buyerRef, accessLayer, bandwidthMbps, postCode, activateMs);
        return o;
    }

    @Transactional(readOnly = true)
    public List<ProviderAccessOrder> list() {
        return orders.findByTenantId(tenantScope.currentTenantId());
    }

    /**
     * Provision the access we sold: sweep acknowledged orders past their activation
     * time, flip them active and notify each retailer's callback. Per-tenant under
     * actAs, once across replicas via the tick guard — the provider's own network
     * completing on its own clock.
     */
    @Scheduled(fixedDelayString = "${bss.wholesale.provider-tick-ms:3000}")
    public void activationTick() {
        if (!tickGuard.claim("wholesale-provider-activate", Duration.ofSeconds(30))) {
            return;
        }
        try {
            for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
                try (var ignored = TenantContext.actAs(tenant.getId())) {
                    List<ProviderAccessOrder> due = orders.findTop100ByTenantIdAndStateAndActivateAtBefore(
                            tenant.getId(), ProviderAccessOrder.ACKNOWLEDGED, OffsetDateTime.now());
                    for (ProviderAccessOrder o : due) {
                        o.setState(ProviderAccessOrder.ACTIVE);
                        o.setActivatedAt(OffsetDateTime.now());
                        o.setLastUpdate(OffsetDateTime.now());
                        orders.save(o);
                        events.publish("ProviderAccessOrderStateChangeEvent", "providerAccessOrder", Map.of(
                                "id", o.getId(), "buyerRef", o.getBuyerRef() == null ? "" : o.getBuyerRef(),
                                "state", o.getState(),
                                "retailerPartyId", o.getRetailerPartyId() == null ? "" : o.getRetailerPartyId()));
                        notifyRetailer(o);
                        log.info("provider: access order {} ACTIVE — notified retailer", o.getId());
                    }
                } catch (Exception e) {
                    log.warn("provider activation tick failed for {}: {}", tenant.getId(), e.getMessage());
                }
            }
        } finally {
            tickGuard.release("wholesale-provider-activate");
        }
    }

    private void notifyRetailer(ProviderAccessOrder o) {
        if (o.getCallbackUrl() == null || o.getCallbackUrl().isBlank()) {
            return;
        }
        try {
            restBuilder.clone().build().post().uri(o.getCallbackUrl())
                    .header("Content-Type", "application/json")
                    .body(Map.of("sonataOrderId", o.getId(), "state", "completed"))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("provider: callback to retailer failed for {}: {}", o.getId(), e.getMessage());
        }
    }
}
