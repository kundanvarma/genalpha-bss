package com.bss.som.service;

import com.bss.som.entity.DealerAgreement;
import com.bss.som.entity.TelesalesOffer;
import com.bss.som.exception.BadRequestException;
import com.bss.som.exception.NotFoundException;
import com.bss.som.repository.TelesalesOfferRepository;
import com.bss.som.security.TenantContext;
import com.bss.som.security.TenantRegistry;
import com.bss.som.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE TELESALES CHANNEL: an outbound partner (their dialer or ours)
 * sells on a call — and the law shapes the flow. The DNC wash runs
 * FAIL-CLOSED before any offer exists: a reserved number is refused,
 * and an unreachable register refuses too, because "we couldn't check"
 * is not consent. The call's output is an OFFER, never an order —
 * under angrerettloven a consumer telesales agreement binds only when
 * the customer confirms IN WRITING after the call. The order (and the
 * partner's commission) is born at confirmation; unconfirmed offers
 * expire on a clock.
 */
@Service
public class TelesalesService {

    private static final Logger log = LoggerFactory.getLogger(TelesalesService.class);
    private static final String TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final TelesalesOfferRepository offers;
    private final DealerService dealers;
    private final com.bss.som.client.PartyOrgClient party;
    private final com.bss.som.events.DomainEventPublisher events;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final RestClient rest;
    private final long expiryMs;

    public TelesalesService(TelesalesOfferRepository offers, DealerService dealers,
            com.bss.som.client.PartyOrgClient party,
            com.bss.som.events.DomainEventPublisher events,
            TenantRegistry tenants, TenantScope tenantScope, RestClient.Builder builder,
            @Value("${bss.som.telesales-expiry-ms:172800000}") long expiryMs) {
        this.offers = offers;
        this.dealers = dealers;
        this.party = party;
        this.events = events;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.rest = builder.build();
        this.expiryMs = expiryMs;
    }

    /**
     * The agent (or the partner's dialer) records the agreement reached
     * on the call. The wash refuses reserved numbers and REFUSES when
     * the register cannot answer — fail-closed is the only lawful
     * default for outbound.
     */
    @Transactional
    public Map<String, Object> offer(Map<String, Object> dto) {
        DealerAgreement dealer = dealers.requireDealerAgreement();
        String tenant = tenantScope.currentTenantId();
        String phone = dto.get("phone") == null ? null : String.valueOf(dto.get("phone"));
        washOrRefuse(tenant, phone);
        String email = String.valueOf(dto.get("customerEmail"));
        Map<String, Object> customer = party.individualByEmail(email)
                .orElseThrow(() -> new BadRequestException(
                        "no customer with that email — v1 telesales sells to the warm base"));
        SecureRandom random = new SecureRandom();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            token.append(TOKEN_ALPHABET.charAt(random.nextInt(TOKEN_ALPHABET.length())));
        }
        TelesalesOffer offer = new TelesalesOffer();
        offer.setId(UUID.randomUUID().toString());
        offer.setTenantId(tenant);
        offer.setDealerOrgId(dealer.getDealerOrgId());
        offer.setStore(dto.get("campaign") == null ? dealer.getName()
                : String.valueOf(dto.get("campaign")));
        offer.setCustomerId(String.valueOf(customer.get("id")));
        offer.setCustomerPhone(phone);
        offer.setOfferingId(String.valueOf(dto.get("offeringId")));
        offer.setOfferingName(dto.get("offeringName") == null ? null
                : String.valueOf(dto.get("offeringName")));
        offer.setConfirmToken(token.toString());
        offer.setStatus(TelesalesOffer.OFFERED);
        offer.setCreatedAt(OffsetDateTime.now());
        offer.setExpiresAt(OffsetDateTime.now().plus(Duration.ofMillis(expiryMs)));
        offer.setLastUpdate(OffsetDateTime.now());
        offers.save(offer);
        // the WRITTEN confirmation rides the notification loop: inbox
        // always, email where the tenant has an ESP
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("offeringName", offer.getOfferingName() == null ? "your new plan"
                : offer.getOfferingName());
        view.put("seller", offer.getStore());
        view.put("confirmToken", offer.getConfirmToken());
        view.put("expiresAt", offer.getExpiresAt().toString());
        view.put("relatedParty", List.of(Map.of("id", offer.getCustomerId(), "role", "customer")));
        view.put("@type", "TelesalesOffer");
        events.publish("TelesalesOfferEvent", "telesalesOffer", view);
        log.info("telesales offer {} by {} to {} — NO order until the customer confirms",
                offer.getId(), dealer.getName(), offer.getCustomerId());
        return Map.of("offerId", offer.getId(), "status", offer.getStatus(),
                "expiresAt", offer.getExpiresAt().toString());
    }

    /** The customer's WRITTEN yes: the token is the capability. Only now
     * is the order born — and with it, the partner's commission. */
    @Transactional
    public Map<String, Object> confirm(String tenantId, String callerPartyId, String token) {
        TelesalesOffer offer = offers.findByTenantIdAndConfirmToken(tenantId,
                        token == null ? "" : token.trim().toUpperCase())
                .orElseThrow(() -> NotFoundException.forResource("TelesalesOffer", "token"));
        if (callerPartyId == null || !callerPartyId.equals(offer.getCustomerId())) {
            // the offer is confirmed by ITS customer, signed in — that is
            // what "in writing" means here; anyone else sees nothing
            throw NotFoundException.forResource("TelesalesOffer", "token");
        }
        if (TelesalesOffer.CONFIRMED.equals(offer.getStatus())) {
            // idempotent: re-clicking the link never orders twice
            return Map.of("status", offer.getStatus(), "productOrderId", offer.getProductOrderId());
        }
        if (!TelesalesOffer.OFFERED.equals(offer.getStatus())
                || offer.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BadRequestException(
                    "this offer has expired — the agent can make you a fresh one");
        }
        String orderId = dealers.placeTelesalesOrder(offer);
        offer.setStatus(TelesalesOffer.CONFIRMED);
        offer.setProductOrderId(orderId);
        offer.setConfirmedAt(OffsetDateTime.now());
        offer.setLastUpdate(OffsetDateTime.now());
        offers.save(offer);
        log.info("telesales offer {} CONFIRMED in writing — order {} exists now, and so does"
                + " the commission", offer.getId(), orderId);
        return Map.of("status", offer.getStatus(), "productOrderId", orderId);
    }

    /** Unconfirmed is unbinding: offers expire on the clock. */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${bss.som.telesales-tick-ms:3600000}")
    public void expiryTick() {
        for (TenantRegistry.TenantEntry tenant : tenants.getRegistry()) {
            try (var ignored = TenantContext.actAs(tenant.getId())) {
                for (TelesalesOffer offer : offers.findTop100ByTenantIdAndStatusAndExpiresAtBefore(
                        tenant.getId(), TelesalesOffer.OFFERED, OffsetDateTime.now())) {
                    offer.setStatus(TelesalesOffer.EXPIRED);
                    offer.setLastUpdate(OffsetDateTime.now());
                    offers.save(offer);
                    log.info("telesales offer {} expired unconfirmed — no agreement ever existed",
                            offer.getId());
                }
            } catch (Exception e) {
                log.warn("telesales expiry tick failed for {}: {}", tenant.getId(), e.getMessage());
            }
        }
    }

    /** The partner's own offers — their pipeline view. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myOffers() {
        DealerAgreement dealer = dealers.requireDealerAgreement();
        return offers.findTop100ByTenantIdAndDealerOrgIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId(), dealer.getDealerOrgId()).stream().map(o -> {
                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                    map.put("id", o.getId());
                    map.put("offeringName", o.getOfferingName());
                    map.put("campaign", o.getStore());
                    map.put("status", o.getStatus());
                    map.put("createdAt", o.getCreatedAt().toString());
                    map.put("@type", "TelesalesOffer");
                    return (Map<String, Object>) map;
                }).toList();
    }

    private void washOrRefuse(String tenantId, String phone) {
        if (phone == null || phone.isBlank()) {
            throw new BadRequestException("outbound needs the number that was dialed");
        }
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || tenant.getDncUrl() == null || tenant.getDncUrl().isBlank()) {
            // an outbound channel WITHOUT a wash configured refuses too:
            // the operator opts in by configuring the register, not out
            throw new BadRequestException(
                    "no do-not-call register configured for this operator — outbound is closed");
        }
        try {
            Map<String, Object> verdict = rest.get()
                    .uri(tenant.getDncUrl() + "/check?phone={p}", phone)
                    .header("Authorization", "Bearer " + tenant.getDncToken())
                    .retrieve().body(Map.class);
            if (verdict == null || Boolean.TRUE.equals(verdict.get("reserved"))) {
                throw new BadRequestException(
                        "this number is on the reservation register — it may not be sold to");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            // FAIL-CLOSED: "we couldn't check" is not consent
            throw new BadRequestException(
                    "the reservation register is unreachable — no wash, no sale");
        }
    }
}
