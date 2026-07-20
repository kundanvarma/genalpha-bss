package com.bss.som.service;

import com.bss.som.client.OrderingClient;
import com.bss.som.client.PartyOrgClient;
import com.bss.som.entity.CommissionEntry;
import com.bss.som.entity.DealerAgreement;
import com.bss.som.entity.StarterKit;
import com.bss.som.exception.BadRequestException;
import com.bss.som.exception.NotFoundException;
import com.bss.som.repository.CommissionEntryRepository;
import com.bss.som.repository.DealerAgreementRepository;
import com.bss.som.repository.StarterKitRepository;
import com.bss.som.security.PartyScope;
import com.bss.som.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE DEALER CHANNEL (the CSP + external-retail model (think Elkjøp/Power)): retail chains sell our
 * activations. Being a dealer IS an agreement row; the clerk's power comes
 * from their org membership, checked live against party management —
 * foreign dealers see NOTHING (404-shaped, never 403). Two ways to sell:
 * the counter (clerk orders on the customer's behalf, dealer-stamped) and
 * the STARTER KIT — attribution baked into the box, so a kit sold like a
 * chocolate bar still credits the store when the customer self-activates
 * at home.
 */
@Service
public class DealerService {

    private static final Logger log = LoggerFactory.getLogger(DealerService.class);
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final DealerAgreementRepository agreements;
    private final StarterKitRepository kits;
    private final CommissionEntryRepository commissions;
    private final PartyOrgClient party;
    private final OrderingClient ordering;
    private final com.bss.som.crypto.PukVault pukVault;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;

    public DealerService(DealerAgreementRepository agreements, StarterKitRepository kits,
            CommissionEntryRepository commissions, PartyOrgClient party, OrderingClient ordering,
            com.bss.som.crypto.PukVault pukVault, TenantScope tenantScope, PartyScope partyScope) {
        this.agreements = agreements;
        this.kits = kits;
        this.commissions = commissions;
        this.party = party;
        this.ordering = ordering;
        this.pukVault = pukVault;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    /**
     * The caller's dealer agreement, by whichever credential speaks:
     * a CLERK is their org membership (checked live against party
     * management); a chain's POS is the MACHINE client the agreement
     * names. Anyone else: 404-shaped nothing.
     */
    private DealerAgreement requireDealer() {
        String tenant = tenantScope.currentTenantId();
        // the MACHINE path first: an agreement that names this OAuth2
        // client wins outright (no agreement ever names a human's client,
        // and service accounts may carry the realm's default person roles)
        String clientId = callerClientId();
        if (clientId != null) {
            List<DealerAgreement> byClient = agreements.findByTenantIdAndClientId(tenant, clientId);
            if (!byClient.isEmpty()) {
                return byClient.get(0);
            }
        }
        String caller = partyScope.scopedPartyId()
                .orElseThrow(() -> NotFoundException.forResource("Dealer", "me"));
        String orgId = party.orgOf(caller)
                .orElseThrow(() -> NotFoundException.forResource("Dealer", caller));
        return agreements.findByTenantIdAndDealerOrgId(tenant, orgId)
                .orElseThrow(() -> NotFoundException.forResource("Dealer", orgId));
    }

    /** The OAuth2 client the token speaks for (Keycloak: azp). */
    private String callerClientId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth instanceof org.springframework.security.oauth2.server.resource.authentication
                .JwtAuthenticationToken jwt) {
            String azp = jwt.getToken().getClaimAsString("azp");
            return azp != null ? azp : jwt.getToken().getClaimAsString("client_id");
        }
        return null;
    }

    private boolean callerHasAuthority(String authority) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> authority.equals(a.getAuthority()));
    }

    /** Back office signs the chain: org + commission per activation. */
    @Transactional
    public Map<String, Object> createAgreement(Map<String, Object> dto) {
        if (partyScope.scopedPartyId().isPresent() || !callerHasAuthority("service:write")) {
            throw new BadRequestException("dealer agreements are a back-office operation");
        }
        String orgId = String.valueOf(dto.get("dealerOrgId"));
        if (orgId == null || "null".equals(orgId)) {
            throw new BadRequestException("dealerOrgId is required");
        }
        String tenant = tenantScope.currentTenantId();
        DealerAgreement agreement = agreements.findByTenantIdAndDealerOrgId(tenant, orgId)
                .orElseGet(() -> {
                    DealerAgreement fresh = new DealerAgreement();
                    fresh.setId(UUID.randomUUID().toString());
                    fresh.setTenantId(tenant);
                    fresh.setDealerOrgId(orgId);
                    fresh.setCreatedAt(OffsetDateTime.now());
                    return fresh;
                });
        agreement.setName(dto.get("name") == null ? orgId : String.valueOf(dto.get("name")));
        if (dto.get("clientId") != null) {
            String clientId = String.valueOf(dto.get("clientId"));
            // one credential speaks for ONE dealer: signing a chain with a
            // client takes that client from any previous holder
            for (DealerAgreement holder : agreements.findByTenantIdAndClientId(tenant, clientId)) {
                if (!holder.getId().equals(agreement.getId())) {
                    holder.setClientId(null);
                    holder.setLastUpdate(OffsetDateTime.now());
                    agreements.save(holder);
                }
            }
            agreement.setClientId(clientId);
        }
        Map<String, Object> commission = dto.get("commission") instanceof Map<?, ?> c
                ? (Map<String, Object>) c : Map.of();
        agreement.setCommissionValue(new BigDecimal(String.valueOf(
                commission.getOrDefault("value", "0"))));
        agreement.setCommissionUnit(String.valueOf(commission.getOrDefault("unit", "EUR")));
        agreement.setLastUpdate(OffsetDateTime.now());
        agreements.save(agreement);
        log.info("dealer agreement: {} earns {} {} per activation", agreement.getName(),
                agreement.getCommissionValue(), agreement.getCommissionUnit());
        return agreementMap(agreement);
    }

    /** A batch of kits for the caller's OWN store — codes humans can read
     * over a counter, SIMs minted operator-side like every other. */
    @Transactional
    public List<Map<String, Object>> mintBatch(Map<String, Object> dto) {
        DealerAgreement dealer = requireDealer();
        int count = Math.min(50, Math.max(1, dto.get("count") == null ? 1
                : Integer.parseInt(String.valueOf(dto.get("count")))));
        String store = dto.get("store") == null ? null : String.valueOf(dto.get("store"));
        SecureRandom random = new SecureRandom();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StringBuilder code = new StringBuilder();
            for (int c = 0; c < 8; c++) {
                code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            }
            StringBuilder iccid = new StringBuilder("8946");
            for (int d = 0; d < 15; d++) {
                iccid.append(random.nextInt(10));
            }
            StarterKit kit = new StarterKit();
            kit.setId(UUID.randomUUID().toString());
            kit.setTenantId(tenantScope.currentTenantId());
            kit.setActivationCode(code.toString());
            kit.setIccid(iccid.toString());
            kit.setPukCiphertext(pukVault.encrypt(
                    String.format("%08d", random.nextInt(100_000_000)), kit.getIccid()));
            kit.setDealerOrgId(dealer.getDealerOrgId());
            kit.setStore(store);
            kit.setStatus(StarterKit.AVAILABLE);
            kit.setCreatedAt(OffsetDateTime.now());
            kit.setLastUpdate(OffsetDateTime.now());
            kits.save(kit);
            out.add(kitMap(kit));
        }
        log.info("{} starter kits minted for {} ({})", count, dealer.getName(), store);
        return out;
    }

    /** The store's own kits — and ONLY the store's own. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> myKits() {
        DealerAgreement dealer = requireDealer();
        return kits.findTop200ByTenantIdAndDealerOrgIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId(), dealer.getDealerOrgId())
                .stream().map(this::kitMap).toList();
    }

    /**
     * THE COUNTER SALE: the clerk orders on the customer's behalf; the
     * order carries BOTH parties — the customer as owner, the dealer as
     * attribution. The customer must already exist (they register in the
     * shop or app); the counter never invents identities.
     */
    public Map<String, Object> sell(Map<String, Object> dto) {
        DealerAgreement dealer = requireDealer();
        String email = String.valueOf(dto.get("customerEmail"));
        Map<String, Object> customer = party.individualByEmail(email)
                .orElseThrow(() -> new BadRequestException(
                        "no customer with that email — ask them to register in the app first"));
        String orderId = placeDealerOrder(dealer, String.valueOf(customer.get("id")),
                dto.get("store") == null ? null : String.valueOf(dto.get("store")),
                String.valueOf(dto.get("offeringId")),
                dto.get("offeringName") == null ? null : String.valueOf(dto.get("offeringName")),
                dto.get("device") == null ? null : String.valueOf(dto.get("device")));
        return Map.of("productOrderId", orderId, "customerId", customer.get("id"));
    }

    /**
     * THE KIT COMES ALIVE: the customer (self) types the code from the box
     * and picks a plan. The order is placed on their behalf carrying the
     * KIT's dealer attribution; when orchestration provisions the service,
     * the kit's own SIM becomes the line's SIM.
     */
    @Transactional
    public Map<String, Object> activateKit(Map<String, Object> dto) {
        String caller = partyScope.scopedPartyId()
                .orElseThrow(() -> new BadRequestException("kit activation is a customer act"));
        String code = String.valueOf(dto.get("code")).trim().toUpperCase();
        StarterKit kit = kits.findByTenantIdAndActivationCode(tenantScope.currentTenantId(), code)
                .orElseThrow(() -> NotFoundException.forResource("StarterKit", code));
        if (!StarterKit.AVAILABLE.equals(kit.getStatus())) {
            throw new BadRequestException("this kit was already activated");
        }
        DealerAgreement dealer = agreements.findByTenantIdAndDealerOrgId(
                tenantScope.currentTenantId(), kit.getDealerOrgId()).orElse(null);
        String orderId = placeDealerOrder(dealer, caller, kit.getStore(),
                String.valueOf(dto.get("offeringId")),
                dto.get("offeringName") == null ? null : String.valueOf(dto.get("offeringName")),
                null);
        kit.setStatus(StarterKit.ACTIVATED);
        kit.setProductOrderId(orderId);
        kit.setActivatedBy(caller);
        kit.setActivatedAt(OffsetDateTime.now());
        kit.setLastUpdate(OffsetDateTime.now());
        kits.save(kit);
        log.info("starter kit {} activated by {} — order {}, credit to {}",
                code, caller, orderId, kit.getDealerOrgId());
        return Map.of("productOrderId", orderId, "iccid", kit.getIccid());
    }

    private String placeDealerOrder(DealerAgreement dealer, String customerId, String store,
            String offeringId, String offeringName, String device) {
        Map<String, Object> offering = new LinkedHashMap<>();
        offering.put("id", offeringId);
        if (offeringName != null) {
            offering.put("name", offeringName);
        }
        List<Map<String, Object>> parties = new ArrayList<>();
        parties.add(Map.of("id", customerId, "role", "customer", "@referredType", "Individual"));
        if (dealer != null) {
            Map<String, Object> stamp = new LinkedHashMap<>();
            stamp.put("id", dealer.getDealerOrgId());
            stamp.put("role", "dealer");
            stamp.put("name", store == null ? dealer.getName() : store);
            stamp.put("@referredType", "Organization");
            if (device != null) {
                // the chain's OWN phone from THEIR stock: context for the
                // commission entry and support — never a billable item here
                stamp.put("device", device);
            }
            parties.add(stamp);
        }
        return ordering.create(Map.of(
                "productOrderItem", List.of(Map.of("action", "add", "productOffering", offering)),
                "relatedParty", parties));
    }

    /**
     * The POS asks "did it activate, what did we earn": the order's
     * services and the commission entry, visible ONLY to the dealer the
     * order credits (activation lands within seconds of a digital sale).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> orderStatus(String productOrderId) {
        DealerAgreement dealer = requireDealer();
        String tenant = tenantScope.currentTenantId();
        List<CommissionEntry> mine = commissions
                .findByTenantIdAndProductOrderId(tenant, productOrderId).stream()
                .filter(e -> dealer.getDealerOrgId().equals(e.getDealerOrgId())).toList();
        if (mine.isEmpty()) {
            throw NotFoundException.forResource("ProductOrder", productOrderId);
        }
        return Map.of(
                "productOrderId", productOrderId,
                "activated", true,
                "commission", mine.stream().map(this::commissionMap).toList());
    }

    /** The telesales sibling needs the same two powers, seam-shaped. */
    DealerAgreement requireDealerAgreement() {
        return requireDealer();
    }

    /** The CONFIRMED telesales agreement becomes the dealer-stamped
     * order — same attribution, same commission machinery. */
    String placeTelesalesOrder(com.bss.som.entity.TelesalesOffer offer) {
        DealerAgreement dealer = agreements.findByTenantIdAndDealerOrgId(
                offer.getTenantId(), offer.getDealerOrgId()).orElse(null);
        return placeDealerOrder(dealer, offer.getCustomerId(), offer.getStore(),
                offer.getOfferingId(), offer.getOfferingName(), null);
    }

    /** The dealer's money page: entries newest first, plus honest totals. */
    @Transactional(readOnly = true)
    public Map<String, Object> myCommission() {
        DealerAgreement dealer = requireDealer();
        List<CommissionEntry> entries = commissions
                .findTop200ByTenantIdAndDealerOrgIdOrderByAccruedAtDesc(
                        tenantScope.currentTenantId(), dealer.getDealerOrgId());
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (CommissionEntry e : entries) {
            totals.merge(e.getStatus(), e.getAmountValue(), BigDecimal::add);
        }
        return Map.of(
                "dealer", dealer.getName(),
                "commissionPerActivation", Map.of("value", dealer.getCommissionValue(),
                        "unit", dealer.getCommissionUnit()),
                "totals", totals,
                "entries", entries.stream().map(this::commissionMap).toList());
    }

    private Map<String, Object> agreementMap(DealerAgreement a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("dealerOrgId", a.getDealerOrgId());
        map.put("name", a.getName());
        map.put("commission", Map.of("value", a.getCommissionValue(), "unit", a.getCommissionUnit()));
        map.put("@type", "DealerAgreement");
        return map;
    }

    private Map<String, Object> kitMap(StarterKit k) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", k.getId());
        map.put("activationCode", k.getActivationCode());
        map.put("iccid", k.getIccid());
        map.put("store", k.getStore());
        map.put("status", k.getStatus());
        map.put("activatedAt", k.getActivatedAt() == null ? null : k.getActivatedAt().toString());
        map.put("@type", "StarterKit");
        return map;
    }

    private Map<String, Object> commissionMap(CommissionEntry e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("store", e.getStore());
        map.put("offeringName", e.getOfferingName());
        map.put("device", e.getDeviceNote());
        map.put("amount", Map.of("value", e.getAmountValue(), "unit", e.getAmountUnit()));
        map.put("status", e.getStatus());
        map.put("reason", e.getReason());
        map.put("accruedAt", e.getAccruedAt().toString());
        map.put("hardensAt", e.getHardensAt().toString());
        map.put("@type", "CommissionEntry");
        return map;
    }
}
