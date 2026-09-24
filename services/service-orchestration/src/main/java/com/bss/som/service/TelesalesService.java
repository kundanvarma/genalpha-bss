package com.bss.som.service;

import com.bss.som.dto.TelesalesDtos.ConfirmReceipt;
import com.bss.som.dto.TelesalesDtos.DialEntry;
import com.bss.som.dto.TelesalesDtos.DialList;
import com.bss.som.dto.TelesalesDtos.OfferReceipt;
import com.bss.som.dto.TelesalesDtos.OfferRequest;
import com.bss.som.dto.TelesalesDtos.OfferRow;
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
import static com.bss.som.mapper.Wire.idOf;

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
    private final com.bss.som.tick.TickGuard tickGuard;
    private final long expiryMs;

    public TelesalesService(TelesalesOfferRepository offers, DealerService dealers,
            com.bss.som.client.PartyOrgClient party,
            com.bss.som.client.InsightClient insight,
            com.bss.som.events.DomainEventPublisher events,
            TenantRegistry tenants, TenantScope tenantScope, RestClient.Builder builder,
            com.bss.som.tick.TickGuard tickGuard,
            @Value("${bss.som.telesales-expiry-ms:172800000}") long expiryMs) {
        this.offers = offers;
        this.dealers = dealers;
        this.party = party;
        this.insight = insight;
        this.events = events;
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.rest = builder.build();
        this.tickGuard = tickGuard;
        this.expiryMs = expiryMs;
    }

    /**
     * The agent (or the partner's dialer) records the agreement reached
     * on the call. The wash refuses reserved numbers and REFUSES when
     * the register cannot answer — fail-closed is the only lawful
     * default for outbound.
     */
    @Transactional
    public OfferReceipt offer(OfferRequest dto) {
        DealerAgreement dealer = dealers.requireDealerAgreement();
        String tenant = tenantScope.currentTenantId();
        String phone = dto.phone();
        washOrRefuse(tenant, phone);
        String email = dto.customerEmail();
        if (email == null || email.isBlank()) {
            throw new BadRequestException("customerEmail is required — the offer is confirmed by that identity");
        }
        // a customer record without an id is no identity yet: the offer then
        // goes the COLD way and identity arrives when they register
        String customerId = idOf(party.individualByEmail(email).orElse(null));
        if (customerId == null && dto.prospectName() == null) {
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
        offer.setStore(dto.campaign() == null ? dealer.getName() : dto.campaign());
        if (customerId != null) {
            offer.setCustomerId(customerId);
        } else {
            // COLD: no identity yet — the offer remembers who was called;
            // identity arrives when they register with this email
            offer.setProspectEmail(email.toLowerCase());
            offer.setProspectName(dto.prospectName());
        }
        offer.setCustomerPhone(phone);
        offer.setOfferingId(String.valueOf(dto.offeringId()));
        offer.setOfferingName(dto.offeringName());
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
        // a COLD prospect's receipt carries the code: the partner's own SMS
        // takes it to them — there is no inbox to put it in yet
        boolean cold = offer.getCustomerId() == null;
        return new OfferReceipt(offer.getId(), offer.getStatus(), offer.getExpiresAt().toString(),
                cold ? offer.getConfirmToken() : null, cold ? Boolean.TRUE : null);
    }

    /** The customer's WRITTEN yes: the token is the capability. Only now
     * is the order born — and with it, the partner's commission. */
    @Transactional
    public ConfirmReceipt confirm(String tenantId, String callerPartyId, String token) {
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
            return new ConfirmReceipt(offer.getStatus(), offer.getProductOrderId());
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
        return new ConfirmReceipt(offer.getStatus(), orderId);
    }

    /** Unconfirmed is unbinding: offers expire on the clock. */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${bss.som.telesales-tick-ms:3600000}")
    public void expiryTick() {
        if (!tickGuard.claim("telesales-expiry", java.time.Duration.ofSeconds(60))) {
            return; // another replica expires the offers
        }
        try {
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
        } finally {
            tickGuard.release("telesales-expiry");
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
    public DialList dialList(String segment) {
        dealers.requireDealerAgreement();
        String tenant = tenantScope.currentTenantId();
        List<DialEntry> entries = new java.util.ArrayList<>();
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
            entries.add(new DialEntry(partyId,
                    (person.getOrDefault("givenName", "") + " " + person.getOrDefault("familyName", "")).trim(),
                    phone, email, "segment-consented, DNC-washed"));
        }
        log.info("dial list '{}': {} callable, {} reserved excluded, {} unwashed excluded",
                segment, entries.size(), reserved, unreachable);
        return new DialList(segment, entries, reserved, unreachable);
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
    public List<OfferRow> myOffers() {
        DealerAgreement dealer = dealers.requireDealerAgreement();
        return offers.findTop100ByTenantIdAndDealerOrgIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId(), dealer.getDealerOrgId()).stream()
                .map(o -> new OfferRow(o.getId(), o.getOfferingName(), o.getStore(), o.getStatus(),
                        o.getCreatedAt().toString(), "TelesalesOffer"))
                .toList();
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
