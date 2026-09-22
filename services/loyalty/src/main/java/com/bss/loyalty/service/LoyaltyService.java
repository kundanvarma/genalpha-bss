package com.bss.loyalty.service;

import com.bss.loyalty.entity.LoyaltyMember;
import com.bss.loyalty.entity.LoyaltyProgram;
import com.bss.loyalty.entity.LoyaltyTransaction;
import com.bss.loyalty.events.DomainEventPublisher;
import com.bss.loyalty.exception.BadRequestException;
import com.bss.loyalty.exception.ConflictException;
import com.bss.loyalty.exception.NotFoundException;
import com.bss.loyalty.repository.LoyaltyMemberRepository;
import com.bss.loyalty.repository.LoyaltyProgramRepository;
import com.bss.loyalty.repository.LoyaltyTransactionRepository;
import com.bss.loyalty.client.PromotionMint;
import com.bss.loyalty.dto.AdjustRequest;
import com.bss.loyalty.dto.LiabilityView;
import com.bss.loyalty.dto.LoyaltyMemberView;
import com.bss.loyalty.dto.LoyaltyProgramRequest;
import com.bss.loyalty.dto.LoyaltyProgramView;
import com.bss.loyalty.dto.LoyaltyTransactionView;
import com.bss.loyalty.dto.RedeemReceipt;
import com.bss.loyalty.dto.Reward;
import com.bss.loyalty.dto.SweepReceipt;
import com.bss.loyalty.dto.TierVerdict;
import com.bss.loyalty.security.PartyScope;
import com.bss.loyalty.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The loyalty ledger. Earning follows the BILLING relationship (a settled
 * bill earns, idempotent per bill); burning maps onto rewards that already
 * exist — gigabytes first, delivered by event to the usage meter and
 * verified THERE, never on this service's word. Membership is opt-in;
 * every movement journals its cause, because points are a liability.
 */
@Service
public class LoyaltyService {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyService.class);

    private final LoyaltyProgramRepository programs;
    private final LoyaltyMemberRepository members;
    private final LoyaltyTransactionRepository txs;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final PromotionMint promotionMint;
    private final com.bss.loyalty.tick.TickGuard tickGuard;

    public LoyaltyService(LoyaltyProgramRepository programs, LoyaltyMemberRepository members,
            LoyaltyTransactionRepository txs, DomainEventPublisher events,
            TenantScope tenantScope, PartyScope partyScope, PromotionMint promotionMint,
            com.bss.loyalty.tick.TickGuard tickGuard) {
        this.programs = programs;
        this.members = members;
        this.txs = txs;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.promotionMint = promotionMint;
        this.tickGuard = tickGuard;
    }

    /* ---------- the program (data, marketer-owned) ---------- */

    @Transactional
    public LoyaltyProgramView upsertProgram(LoyaltyProgramRequest dto) {
        String tenant = tenantScope.currentTenantId();
        LoyaltyProgram p = programs.findByTenantId(tenant).orElseGet(LoyaltyProgram::new);
        p.setTenantId(tenant);
        if (dto.enabled() != null) {
            p.setEnabled(dto.enabled());
        }
        if (dto.earnPointsPerCurrency() != null) {
            p.setEarnPointsPerCurrency(dto.earnPointsPerCurrency());
        }
        if (dto.pointsPerGb() != null) {
            p.setPointsPerGb(dto.pointsPerGb());
        }
        if (dto.expiryMonths() != null) {
            p.setExpiryMonths(dto.expiryMonths());
        }
        if (dto.voucherPercent() != null) {
            p.setVoucherPercent(dto.voucherPercent());
        }
        if (dto.pointsPerVoucher() != null) {
            p.setPointsPerVoucher(dto.pointsPerVoucher());
        }
        if (dto.silverThreshold() != null) {
            p.setSilverThreshold(dto.silverThreshold());
        }
        if (dto.goldThreshold() != null) {
            p.setGoldThreshold(dto.goldThreshold());
        }
        p.setLastUpdate(OffsetDateTime.now());
        return LoyaltyProgramView.of(programs.save(p));
    }

    @Transactional(readOnly = true)
    public LoyaltyProgramView program() {
        return LoyaltyProgramView.of(programs.findByTenantId(tenantScope.currentTenantId())
                .orElseThrow(() -> new NotFoundException("no loyalty program for this operator")));
    }

    /* ---------- membership (opt-in, self) ---------- */

    @Transactional
    public LoyaltyMemberView enroll() {
        String tenant = tenantScope.currentTenantId();
        LoyaltyProgram p = programs.findByTenantId(tenant).orElse(null);
        if (p == null || !p.isEnabled()) {
            throw new ConflictException("this operator runs no loyalty program");
        }
        String party = requireSelf();
        LoyaltyMember m = members.findByIdAndTenantId(party, tenant).orElse(null);
        if (m != null) {
            return LoyaltyMemberView.of(m); // enrolling twice is a no-op, not an error
        }
        m = new LoyaltyMember();
        m.setId(party);
        m.setTenantId(tenant);
        m.setBalance(0);
        m.setEnrolledAt(OffsetDateTime.now());
        m.setLastUpdate(OffsetDateTime.now());
        return LoyaltyMemberView.of(members.save(m));
    }

    @Transactional(readOnly = true)
    public LoyaltyMemberView me() {
        LoyaltyMember m = members.findByIdAndTenantId(requireSelf(), tenantScope.currentTenantId())
                .orElseThrow(() -> new NotFoundException("not a loyalty member"));
        return LoyaltyMemberView.of(m);
    }

    @Transactional(readOnly = true)
    public List<LoyaltyTransactionView> myJournal() {
        return txs.findTop50ByTenantIdAndPartyIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId(), requireSelf())
                .stream().map(LoyaltyTransactionView::of).toList();
    }

    /* ---------- earning: the settled bill (idempotent per bill) ---------- */

    @Transactional
    public void earnOnSettledBill(String tenantId, String partyId, String billId, BigDecimal amount) {
        LoyaltyProgram p = programs.findByTenantId(tenantId).orElse(null);
        if (p == null || !p.isEnabled() || partyId == null || amount == null) {
            return;
        }
        LoyaltyMember m = members.findByIdAndTenantId(partyId, tenantId).orElse(null);
        if (m == null) {
            return; // opt-in: a non-member's bill earns nothing
        }
        String cause = "bill:" + billId;
        if (txs.existsByTenantIdAndCause(tenantId, cause)) {
            return; // at-least-once delivery, exactly-once earning
        }
        long points = amount.multiply(p.getEarnPointsPerCurrency())
                .setScale(0, RoundingMode.FLOOR).longValue();
        if (points <= 0) {
            return;
        }
        journal(tenantId, partyId, LoyaltyTransaction.EARN, points, cause);
        m.setBalance(m.getBalance() + points);
        m.setLastUpdate(OffsetDateTime.now());
        members.save(m);
        log.info("loyalty: {} earned {} points on {} ({} {})", partyId, points, billId, amount, tenantId);
    }

    /* ---------- burning: gigabytes, delivered by event ---------- */

    @Transactional
    public RedeemReceipt redeemData(int gb) {
        if (gb < 1 || gb > 50) {
            throw new BadRequestException("redeem between 1 and 50 GB");
        }
        String tenant = tenantScope.currentTenantId();
        LoyaltyProgram p = programs.findByTenantId(tenant)
                .orElseThrow(() -> new ConflictException("this operator runs no loyalty program"));
        String party = requireSelf();
        LoyaltyMember m = members.findByIdAndTenantId(party, tenant)
                .orElseThrow(() -> new NotFoundException("not a loyalty member"));
        long cost = (long) p.getPointsPerGb() * gb;
        if (m.getBalance() < cost) {
            throw new ConflictException("insufficient points: have " + m.getBalance()
                    + ", need " + cost);
        }
        String redemptionId = UUID.randomUUID().toString();
        journal(tenant, party, LoyaltyTransaction.BURN, -cost,
                "redeem:data:" + gb + "GB:" + redemptionId);
        m.setBalance(m.getBalance() - cost);
        m.setLastUpdate(OffsetDateTime.now());
        members.save(m);
        // the reward rides the outbox — usage adds the GB to THIS month's
        // meter, idempotent per redemptionId; the suite verifies at the meter
        Map<String, Object> reward = new LinkedHashMap<>();
        reward.put("redemptionId", redemptionId);
        reward.put("partyId", party);
        reward.put("gb", gb);
        events.publish("LoyaltyDataRewardEvent", "loyaltyReward", reward);
        return new RedeemReceipt(LoyaltyMemberView.of(m), new Reward.Data(gb, cost, redemptionId));
    }

    /* ---------- the liability (the number finance books) ---------- */

    @Transactional(readOnly = true)
    public LiabilityView liability() {
        return LiabilityView.of(members.liability(tenantScope.currentTenantId()));
    }

    /* ---------- vouchers: a real promotion, unique per redemption ---------- */

    @Transactional
    public RedeemReceipt redeemVoucher() {
        String tenant = tenantScope.currentTenantId();
        LoyaltyProgram p = programs.findByTenantId(tenant)
                .orElseThrow(() -> new ConflictException("this operator runs no loyalty program"));
        String party = requireSelf();
        LoyaltyMember m = members.findByIdAndTenantId(party, tenant)
                .orElseThrow(() -> new NotFoundException("not a loyalty member"));
        long cost = p.getPointsPerVoucher();
        if (m.getBalance() < cost) {
            throw new ConflictException("insufficient points: have " + m.getBalance() + ", need " + cost);
        }
        String code = "LOYAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // mint FIRST (a failed mint must not burn points), then journal+debit
        promotionMint.mint(code, p.getVoucherPercent(), null);
        journal(tenant, party, LoyaltyTransaction.BURN, -cost, "redeem:voucher:" + code);
        m.setBalance(m.getBalance() - cost);
        m.setLastUpdate(OffsetDateTime.now());
        members.save(m);
        return new RedeemReceipt(LoyaltyMemberView.of(m),
                new Reward.Voucher(code, p.getVoucherPercent(), cost));
    }

    /* ---------- tiers: computed from the rolling year, benefits as policy ---------- */

    private void recomputeTier(LoyaltyProgram p, LoyaltyMember m) {
        long yearEarn = txs.earnedSince(m.getTenantId(), m.getId(),
                OffsetDateTime.now().minusDays(365));
        String tier = yearEarn >= p.getGoldThreshold() ? "gold"
                : yearEarn >= p.getSilverThreshold() ? "silver" : "bronze";
        if (!tier.equals(m.getTier())) {
            String was = m.getTier();
            m.setTier(tier);
            events.publish("LoyaltyTierChangedEvent", "loyaltyMember", Map.of(
                    "relatedParty", java.util.List.of(Map.of("id", m.getId(), "role", "customer")),
                    "tier", tier, "previousTier", was));
            log.info("loyalty: {} moved {} -> {} (year earn {})", m.getId(), was, tier, yearEarn);
        }
    }

    /** Machine/staff read: the tier billing puts into the pricing context. */
    @Transactional(readOnly = true)
    public TierVerdict tierOf(String partyId) {
        return new TierVerdict(partyId,
                members.findByIdAndTenantId(partyId, tenantScope.currentTenantId())
                        .map(LoyaltyMember::getTier).orElse("none"));
    }

    /* ---------- operator adjustment (service recovery / goodwill) ---------- */

    @Transactional
    public LoyaltyMemberView adjust(AdjustRequest body) {
        String tenant = tenantScope.currentTenantId();
        LoyaltyProgram p = programs.findByTenantId(tenant)
                .orElseThrow(() -> new ConflictException("this operator runs no loyalty program"));
        String party = String.valueOf(body.partyId());
        long points = body.pointsOrZero();
        String reason = body.reason();
        if (points == 0 || reason == null || reason.isBlank()) {
            throw new BadRequestException("points (non-zero) and reason are required — goodwill has a cause too");
        }
        LoyaltyMember m = members.findByIdAndTenantId(party, tenant)
                .orElseThrow(() -> new NotFoundException("not a loyalty member"));
        journal(tenant, party, points > 0 ? LoyaltyTransaction.EARN : LoyaltyTransaction.BURN,
                points, "adjust:" + reason + ":" + UUID.randomUUID().toString().substring(0, 8));
        m.setBalance(Math.max(0, m.getBalance() + points));
        m.setLastUpdate(OffsetDateTime.now());
        recomputeTier(p, m);
        members.save(m);
        return LoyaltyMemberView.of(m);
    }

    /* ---------- expiry: points are a liability with a clock ---------- */

    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${bss.loyalty.expiry-sweep-ms:3600000}",
            initialDelayString = "${bss.loyalty.expiry-initial-delay-ms:120000}")
    public void scheduledExpiry() {
        expirySweep();
    }

    @Transactional
    public SweepReceipt expirySweep() {
        if (!tickGuard.claim("loyalty-expiry", java.time.Duration.ofMinutes(5))) {
            return SweepReceipt.Skipped.ANOTHER_REPLICA;
        }
        try {
            String tenant = tenantScope.currentTenantId();
            LoyaltyProgram p = programs.findByTenantId(tenant).orElse(null);
            if (p == null || p.getExpiryMonths() <= 0) {
                return SweepReceipt.NoExpiry.NONE;
            }
            OffsetDateTime cutoff = OffsetDateTime.now().minusMonths(p.getExpiryMonths());
            long expiredTotal = 0;
            int membersTouched = 0;
            for (LoyaltyMember m : members.findAll()) {
                if (!tenant.equals(m.getTenantId()) || m.getBalance() <= 0) {
                    continue;
                }
                // FIFO by arithmetic: what was earned before the cutoff and
                // never spent (nor previously expired) is what expires now
                long earnedOld = txs.earnedBefore(tenant, m.getId(), cutoff);
                long spent = txs.spentTotal(tenant, m.getId());
                long expirable = Math.min(m.getBalance(), Math.max(0, earnedOld - spent));
                if (expirable <= 0) {
                    continue;
                }
                journal(tenant, m.getId(), LoyaltyTransaction.BURN, -expirable,
                        "expiry:" + cutoff.toLocalDate() + ":" + UUID.randomUUID().toString().substring(0, 8));
                m.setBalance(m.getBalance() - expirable);
                m.setLastUpdate(OffsetDateTime.now());
                members.save(m);
                expiredTotal += expirable;
                membersTouched++;
            }
            log.info("loyalty expiry: {} points expired across {} members ({})",
                    expiredTotal, membersTouched, tenant);
            return new SweepReceipt.Swept(expiredTotal, membersTouched);
        } finally {
            tickGuard.release("loyalty-expiry");
        }
    }

    /* ---------- plumbing ---------- */

    private void journal(String tenant, String party, String type, long points, String cause) {
        LoyaltyTransaction t = new LoyaltyTransaction();
        t.setId(UUID.randomUUID().toString());
        t.setTenantId(tenant);
        t.setPartyId(party);
        t.setTxType(type);
        t.setPoints(points);
        t.setCause(cause);
        t.setCreatedAt(OffsetDateTime.now());
        txs.save(t);
    }

    private String requireSelf() {
        return partyScope.scopedPartyId()
                .orElseThrow(() -> new BadRequestException(
                        "loyalty membership is personal — a customer token is required"));
    }

}
