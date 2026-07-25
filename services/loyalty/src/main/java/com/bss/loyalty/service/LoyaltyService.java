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

    public LoyaltyService(LoyaltyProgramRepository programs, LoyaltyMemberRepository members,
            LoyaltyTransactionRepository txs, DomainEventPublisher events,
            TenantScope tenantScope, PartyScope partyScope) {
        this.programs = programs;
        this.members = members;
        this.txs = txs;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    /* ---------- the program (data, marketer-owned) ---------- */

    @Transactional
    public Map<String, Object> upsertProgram(Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        LoyaltyProgram p = programs.findByTenantId(tenant).orElseGet(LoyaltyProgram::new);
        p.setTenantId(tenant);
        if (dto.get("enabled") != null) {
            p.setEnabled(Boolean.parseBoolean(String.valueOf(dto.get("enabled"))));
        }
        if (dto.get("earnPointsPerCurrency") != null) {
            p.setEarnPointsPerCurrency(new BigDecimal(String.valueOf(dto.get("earnPointsPerCurrency"))));
        }
        if (dto.get("pointsPerGb") != null) {
            p.setPointsPerGb(Integer.parseInt(String.valueOf(dto.get("pointsPerGb"))));
        }
        p.setLastUpdate(OffsetDateTime.now());
        return programView(programs.save(p));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> program() {
        return programView(programs.findByTenantId(tenantScope.currentTenantId())
                .orElseThrow(() -> new NotFoundException("no loyalty program for this operator")));
    }

    /* ---------- membership (opt-in, self) ---------- */

    @Transactional
    public Map<String, Object> enroll() {
        String tenant = tenantScope.currentTenantId();
        LoyaltyProgram p = programs.findByTenantId(tenant).orElse(null);
        if (p == null || !p.isEnabled()) {
            throw new ConflictException("this operator runs no loyalty program");
        }
        String party = requireSelf();
        LoyaltyMember m = members.findByIdAndTenantId(party, tenant).orElse(null);
        if (m != null) {
            return memberView(m); // enrolling twice is a no-op, not an error
        }
        m = new LoyaltyMember();
        m.setId(party);
        m.setTenantId(tenant);
        m.setBalance(0);
        m.setEnrolledAt(OffsetDateTime.now());
        m.setLastUpdate(OffsetDateTime.now());
        return memberView(members.save(m));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> me() {
        LoyaltyMember m = members.findByIdAndTenantId(requireSelf(), tenantScope.currentTenantId())
                .orElseThrow(() -> new NotFoundException("not a loyalty member"));
        return memberView(m);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> myJournal() {
        return txs.findTop50ByTenantIdAndPartyIdOrderByCreatedAtDesc(
                tenantScope.currentTenantId(), requireSelf())
                .stream().map(this::txView).toList();
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
    public Map<String, Object> redeemData(int gb) {
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
        Map<String, Object> out = memberView(m);
        out.put("redeemed", Map.of("gb", gb, "points", cost, "redemptionId", redemptionId));
        return out;
    }

    /* ---------- the liability (the number finance books) ---------- */

    @Transactional(readOnly = true)
    public Map<String, Object> liability() {
        long total = members.liability(tenantScope.currentTenantId());
        return Map.of("outstandingPoints", total,
                "definition", "sum of all member balances — the operator's points liability");
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

    private Map<String, Object> programView(LoyaltyProgram p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", p.isEnabled());
        map.put("earnPointsPerCurrency", p.getEarnPointsPerCurrency());
        map.put("pointsPerGb", p.getPointsPerGb());
        map.put("@type", "LoyaltyProgramSpecification");
        return map;
    }

    private Map<String, Object> memberView(LoyaltyMember m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("balance", m.getBalance());
        map.put("enrolledAt", m.getEnrolledAt());
        map.put("@type", "LoyaltyProgramMember");
        return map;
    }

    private Map<String, Object> txView(LoyaltyTransaction t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", t.getTxType());
        map.put("points", t.getPoints());
        map.put("cause", t.getCause());
        map.put("createdAt", t.getCreatedAt());
        return map;
    }
}
