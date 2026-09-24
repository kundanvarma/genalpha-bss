package com.bss.som.service;

import com.bss.som.client.OrderingClient;
import com.bss.som.client.PartyOrgClient;
import com.bss.som.dto.DealerDtos.AgreementRequest;
import com.bss.som.dto.DealerDtos.CommissionPage;
import com.bss.som.dto.DealerDtos.CommissionView;
import com.bss.som.dto.DealerDtos.DealerAgreementView;
import com.bss.som.dto.DealerDtos.KitActivationReceipt;
import com.bss.som.dto.DealerDtos.KitActivationRequest;
import com.bss.som.dto.DealerDtos.KitBatchRequest;
import com.bss.som.dto.DealerDtos.LeaderboardRow;
import com.bss.som.dto.DealerDtos.OrderStatus;
import com.bss.som.dto.DealerDtos.SaleReceipt;
import com.bss.som.dto.DealerDtos.SaleRequest;
import com.bss.som.dto.DealerDtos.StarterKitView;
import com.bss.som.dto.Money;
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
import static com.bss.som.mapper.Wire.idOf;

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
    public DealerAgreementView createAgreement(AgreementRequest dto) {
        if (partyScope.scopedPartyId().isPresent() || !callerHasAuthority("service:write")) {
            throw new BadRequestException("dealer agreements are a back-office operation");
        }
        String orgId = dto.dealerOrgId();
        if (orgId == null) {
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
        agreement.setName(dto.name() == null ? orgId : dto.name());
        if (dto.clientId() != null) {
            String clientId = dto.clientId();
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
        Money commission = dto.commission();
        agreement.setCommissionValue(commission == null || commission.value() == null
                ? BigDecimal.ZERO : commission.value());
        agreement.setCommissionUnit(commission == null || commission.unit() == null ? "EUR" : commission.unit());
        agreement.setLastUpdate(OffsetDateTime.now());
        agreements.save(agreement);
        log.info("dealer agreement: {} earns {} {} per activation", agreement.getName(),
                agreement.getCommissionValue(), agreement.getCommissionUnit());
        return agreementView(agreement);
    }

    /** A batch of kits for the caller's OWN store — codes humans can read
     * over a counter, SIMs minted operator-side like every other. */
    @Transactional
    public List<StarterKitView> mintBatch(KitBatchRequest dto) {
        DealerAgreement dealer = requireDealer();
        int count = Math.min(50, Math.max(1, dto.count() == null ? 1 : dto.count()));
        String store = dto.store();
        SecureRandom random = new SecureRandom();
        List<StarterKitView> out = new ArrayList<>();
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
            out.add(kitView(kit));
        }
        log.info("{} starter kits minted for {} ({})", count, dealer.getName(), store);
        return out;
    }

    /** The store's own kits — and ONLY the store's own. */
    @Transactional(readOnly = true)
    public List<StarterKitView> myKits() {
        DealerAgreement dealer = requireDealer();
        return kits.findTop200ByTenantIdAndDealerOrgIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId(), dealer.getDealerOrgId())
                .stream().map(this::kitView).toList();
    }

    /**
     * THE COUNTER SALE: the clerk orders on the customer's behalf; the
     * order carries BOTH parties — the customer as owner, the dealer as
     * attribution. The customer must already exist (they register in the
     * shop or app); the counter never invents identities.
     */
    public SaleReceipt sell(SaleRequest dto) {
        DealerAgreement dealer = requireDealer();
        if (dto.customerEmail() == null || dto.customerEmail().isBlank()) {
            throw new BadRequestException("customerEmail is required — the counter sells to a registered customer");
        }
        Map<String, Object> customer = party.individualByEmail(dto.customerEmail())
                .orElseThrow(() -> new BadRequestException(
                        "no customer with that email — ask them to register in the app first"));
        String customerId = idOf(customer);
        if (customerId == null) {
            // party answered with a record that names nobody — that is party's
            // fault, not the clerk's, and no order is placed for customer "null"
            throw new IllegalStateException("the customer record for that email carries no id — sale not placed");
        }
        String orderId = placeDealerOrder(dealer, customerId, dto.store(), dto.offeringId(), dto.offeringName(),
                dto.device());
        return new SaleReceipt(orderId, customerId);
    }

    /**
     * THE KIT COMES ALIVE: the customer (self) types the code from the box
     * and picks a plan. The order is placed on their behalf carrying the
     * KIT's dealer attribution; when orchestration provisions the service,
     * the kit's own SIM becomes the line's SIM.
     */
    @Transactional
    public KitActivationReceipt activateKit(KitActivationRequest dto) {
        String caller = partyScope.scopedPartyId()
                .orElseThrow(() -> new BadRequestException("kit activation is a customer act"));
        if (dto.code() == null || dto.code().isBlank()) {
            throw new BadRequestException("code is required — the code printed in the box");
        }
        String code = dto.code().trim().toUpperCase();
        StarterKit kit = kits.findByTenantIdAndActivationCode(tenantScope.currentTenantId(), code)
                .orElseThrow(() -> NotFoundException.forResource("StarterKit", code));
        if (!StarterKit.AVAILABLE.equals(kit.getStatus())) {
            throw new BadRequestException("this kit was already activated");
        }
        DealerAgreement dealer = agreements.findByTenantIdAndDealerOrgId(
                tenantScope.currentTenantId(), kit.getDealerOrgId()).orElse(null);
        String orderId = placeDealerOrder(dealer, caller, kit.getStore(), dto.offeringId(), dto.offeringName(), null);
        kit.setStatus(StarterKit.ACTIVATED);
        kit.setProductOrderId(orderId);
        kit.setActivatedBy(caller);
        kit.setActivatedAt(OffsetDateTime.now());
        kit.setLastUpdate(OffsetDateTime.now());
        kits.save(kit);
        log.info("starter kit {} activated by {} — order {}, credit to {}",
                code, caller, orderId, kit.getDealerOrgId());
        return new KitActivationReceipt(orderId, kit.getIccid());
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
    public OrderStatus orderStatus(String productOrderId) {
        DealerAgreement dealer = requireDealer();
        String tenant = tenantScope.currentTenantId();
        List<CommissionEntry> mine = commissions
                .findByTenantIdAndProductOrderId(tenant, productOrderId).stream()
                .filter(e -> dealer.getDealerOrgId().equals(e.getDealerOrgId())).toList();
        if (mine.isEmpty()) {
            throw NotFoundException.forResource("ProductOrder", productOrderId);
        }
        return new OrderStatus(productOrderId, true, mine.stream().map(this::commissionView).toList());
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

    /** A store's running tally while the ledger is walked; frozen into a row at the end. */
    private static final class Tally {
        final String dealerOrgId;
        final String store;
        final String unit;
        long activations;
        BigDecimal commission = BigDecimal.ZERO;

        Tally(String dealerOrgId, String store, String unit) {
            this.dealerOrgId = dealerOrgId;
            this.store = store;
            this.unit = unit;
        }
    }

    /** G4 — CHANNEL GAMIFICATION: the season leaderboard the commission
     *  ledger always contained. Stores ranked by accrued commission and
     *  activations; the operator's sell-side scoreboard, sold-side honest. */
    @Transactional(readOnly = true)
    public List<LeaderboardRow> leaderboard() {
        Map<String, Tally> byKey = new LinkedHashMap<>();
        for (CommissionEntry e : commissions.findByTenantId(tenantScope.currentTenantId())) {
            String key = e.getDealerOrgId() + "|" + (e.getStore() == null ? "" : e.getStore());
            Tally row = byKey.computeIfAbsent(key, k -> new Tally(e.getDealerOrgId(), e.getStore(), e.getAmountUnit()));
            row.activations++;
            row.commission = row.commission.add(e.getAmountValue() == null ? BigDecimal.ZERO : e.getAmountValue());
        }
        List<Tally> sorted = new ArrayList<>(byKey.values());
        sorted.sort((a, b) -> b.commission.compareTo(a.commission));
        List<LeaderboardRow> out = new ArrayList<>();
        int rank = 0;
        for (Tally t : sorted) {
            out.add(new LeaderboardRow(t.dealerOrgId, t.store, t.activations, t.commission, t.unit, ++rank));
        }
        return out;
    }

    /** The dealer's money page: entries newest first, plus honest totals. */
    public CommissionPage myCommission() {
        DealerAgreement dealer = requireDealer();
        List<CommissionEntry> entries = commissions
                .findTop200ByTenantIdAndDealerOrgIdOrderByAccruedAtDesc(
                        tenantScope.currentTenantId(), dealer.getDealerOrgId());
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (CommissionEntry e : entries) {
            totals.merge(e.getStatus(), e.getAmountValue(), BigDecimal::add);
        }
        return new CommissionPage(dealer.getName(),
                new Money(dealer.getCommissionValue(), dealer.getCommissionUnit()), totals,
                entries.stream().map(this::commissionView).toList());
    }

    private DealerAgreementView agreementView(DealerAgreement a) {
        return new DealerAgreementView(a.getId(), a.getDealerOrgId(), a.getName(),
                new Money(a.getCommissionValue(), a.getCommissionUnit()), "DealerAgreement");
    }

    private StarterKitView kitView(StarterKit k) {
        return new StarterKitView(k.getId(), k.getActivationCode(), k.getIccid(), k.getStore(), k.getStatus(),
                k.getActivatedAt() == null ? null : k.getActivatedAt().toString(), "StarterKit");
    }

    private CommissionView commissionView(CommissionEntry e) {
        return new CommissionView(e.getId(), e.getStore(), e.getOfferingName(), e.getDeviceNote(),
                new Money(e.getAmountValue(), e.getAmountUnit()), e.getStatus(), e.getReason(),
                e.getAccruedAt().toString(), e.getHardensAt().toString(), "CommissionEntry");
    }
}
