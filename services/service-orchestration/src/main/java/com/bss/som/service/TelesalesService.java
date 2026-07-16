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
    private final com.bss.som.client.InsightClient insight;
    private final com.bss.som.events.DomainEventPublisher events;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final RestClient rest;
    private final long expiryMs;

    public TelesalesService(TelesalesOfferRepository offers, DealerService dealers,
            com.bss.som.client.PartyOrgClient party,
            com.bss.som.client.InsightClient insight,
            com.bss.som.events.DomainEventPublisher events,
            TenantRegistry tenants, TenantScope tenantScope, RestClient.Builder builder,
            @Value("${bss.som.telesales-expiry-ms:172800000}") long expiryMs) {
        this.offers = offers;
        this.dealers = dealers;
        this.party = party;
        this.insight = insight;
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
        Map<String, Object> customer = party.individualByEmail(email).orElse(null);
        if (customer == null && dto.get("prospectName") == null) {
            throw new BadRequestException(
                    "no customer with that email — for a COLD prospect, send prospectName too");
        }
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
        if (customer != null) {
            offer.setCustomerId(String.valueOf(customer.get("id")));
        } else {
            // COLD: no identity yet — the offer remembers who was called;
            // identity arrives when they register with this email
            offer.setProspectEmail(email.toLowerCase());
            offer.setProspectName(String.valueOf(dto.get("prospectName")));
        }
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
        if (offer.getCustomerId() != null) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("offeringName", offer.getOfferingName() == null ? "your new plan"
                    : offer.getOfferingName());
            view.put("seller", offer.getStore());
            view.put("confirmToken", offer.getConfirmToken());
            view.put("expiresAt", offer.getExpiresAt().toString());
            view.put("relatedParty", List.of(Map.of("id", offer.getCustomerId(), "role", "customer")));
            view.put("@type", "TelesalesOffer");
            events.publish("TelesalesOfferEvent", "telesalesOffer", view);
        }
        log.info("telesales offer {} by {} to {} — NO order until the customer confirms",
                offer.getId(), dealer.getName(), offer.getCustomerId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("offerId", offer.getId());
        out.put("status", offer.getStatus());
        out.put("expiresAt", offer.getExpiresAt().toString());
        if (offer.getCustomerId() == null) {
            // the partner's own SMS carries the code to a cold prospect —
            // there is no inbox to put it in yet
            out.put("confirmToken", offer.getConfirmToken());
            out.put("prospect", true);
        }
        return out;
    }

    /** The customer's WRITTEN yes: the token is the capability. Only now
     * is the order born — and with it, the partner's commission. */
    @Transactional
    public Map<String, Object> confirm(String tenantId, String callerPartyId, String token) {
        TelesalesOffer offer = offers.findByTenantIdAndConfirmToken(tenantId,
                        token == null ? "" : token.trim().toUpperCase())
                .orElseThrow(() -> NotFoundException.forResource("TelesalesOffer", "token"));
        if (callerPartyId == null) {
            throw NotFoundException.forResource("TelesalesOffer", "token");
        }
        if (offer.getCustomerId() == null) {
            // COLD prospect: registering with the offered email IS the
            // identity proof — the caller's party must carry that address
            boolean isProspect = party.individualOf(callerPartyId)
                    .map(p -> p.get("contactMedium") instanceof List<?> media
                            && media.stream().anyMatch(m -> m instanceof Map<?, ?> med
                                && med.get("characteristic") instanceof Map<?, ?> c
                                && offer.getProspectEmail().equalsIgnoreCase(
                                        String.valueOf(c.get("emailAddress")))))
                    .orElse(false);
            if (!isProspect) {
                throw NotFoundException.forResource("TelesalesOffer", "token");
            }
            offer.setCustomerId(callerPartyId);
        } else if (!callerPartyId.equals(offer.getCustomerId())) {
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

    /**
     * THE DIAL LIST: the partner's dialer pulls its audience from the
     * SAME segments campaigns use — consent filtered at the source
     * (insight returns only consented members), and every number WASHED
     * against the reservation register before it appears. Reserved
     * citizens are excluded and counted, never listed.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> dialList(String segment) {
        dealers.requireDealerAgreement();
        String tenant = tenantScope.currentTenantId();
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        int reserved = 0;
        int unreachable = 0;
        for (String partyId : insight.segmentMembers(segment)) {
            Map<String, Object> person = party.individualOf(partyId).orElse(null);
            if (person == null) {
                continue;
            }
            String phone = phoneOf(person);
            String email = emailOf(person);
            if (phone == null) {
                continue; // no number, no call
            }
            try {
                if (isReserved(tenant, phone)) {
                    reserved++;
                    continue; // a reserved citizen is EXCLUDED, never listed
                }
            } catch (Exception e) {
                unreachable++;
                continue; // fail-closed per number: unwashed is uncallable
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("partyId", partyId);
            entry.put("name", (person.getOrDefault("givenName", "") + " "
                    + person.getOrDefault("familyName", "")).trim());
            entry.put("phone", phone);
            entry.put("email", email);
            entry.put("consent", "segment-consented, DNC-washed");
            entries.add(entry);
        }
        log.info("dial list '{}': {} callable, {} reserved excluded, {} unwashed excluded",
                segment, entries.size(), reserved, unreachable);
        return Map.of("segment", segment, "entries", entries,
                "reservedExcluded", reserved, "unwashedExcluded", unreachable);
    }

    @SuppressWarnings("unchecked")
    private static String phoneOf(Map<String, Object> person) {
        if (!(person.get("contactMedium") instanceof List<?> media)) {
            return null;
        }
        for (Object m : media) {
            if (m instanceof Map<?, ?> med && med.get("characteristic") instanceof Map<?, ?> c) {
                Object number = c.get("phoneNumber") != null ? c.get("phoneNumber") : c.get("contactMedium");
                String type = String.valueOf(med.get("mediumType"));
                if (number != null && ("phone".equalsIgnoreCase(type) || "mobile".equalsIgnoreCase(type))) {
                    return String.valueOf(number);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String emailOf(Map<String, Object> person) {
        if (!(person.get("contactMedium") instanceof List<?> media)) {
            return null;
        }
        for (Object m : media) {
            if (m instanceof Map<?, ?> med && "email".equalsIgnoreCase(String.valueOf(med.get("mediumType")))
                    && med.get("characteristic") instanceof Map<?, ?> c && c.get("emailAddress") != null) {
                return String.valueOf(c.get("emailAddress"));
            }
        }
        return null;
    }

    /** One washed number; throws when the register cannot answer. */
    private boolean isReserved(String tenantId, String phone) {
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantId);
        if (tenant == null || tenant.getDncUrl() == null || tenant.getDncUrl().isBlank()) {
            throw new IllegalStateException("no register configured");
        }
        Map<String, Object> verdict = rest.get()
                .uri(tenant.getDncUrl() + "/check?phone={p}", phone)
                .header("Authorization", "Bearer " + tenant.getDncToken())
                .retrieve().body(Map.class);
        return verdict == null || Boolean.TRUE.equals(verdict.get("reserved"));
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
