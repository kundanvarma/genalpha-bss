package com.bss.campaign.service;

import com.bss.campaign.entity.ReferralCode;
import com.bss.campaign.entity.ReferralConversion;
import com.bss.campaign.events.DomainEventPublisher;
import com.bss.campaign.exception.BadRequestException;
import com.bss.campaign.exception.NotFoundException;
import com.bss.campaign.repository.ReferralCodeRepository;
import com.bss.campaign.repository.ReferralConversionRepository;
import com.bss.campaign.security.PartyScope;
import com.bss.campaign.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * G1 — MEMBER-GET-MEMBER, the honest-game way. A customer's code is theirs
 * forever; a joiner redeems it once; the reward (DATA, both sides) pays only
 * when the joiner's FIRST ORDER COMPLETES — a referral is a customer, not a
 * click. The velocity guard HOLDS suspicious payouts instead of paying them
 * (>N conversions per referrer per day — the classic farm signal); holds are
 * a worklist, not a silent drop. Rewards ride the same event rail as loyalty
 * redemptions, so the meter credit is idempotent and suite-provable.
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReferralCodeRepository codes;
    private final ReferralConversionRepository conversions;
    private final com.bss.campaign.repository.CommunityGoalRepository goals;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final BigDecimal rewardGb;
    private final int velocityPerDay;
    private final BigDecimal clubShareAmount;
    private final String clubShareCurrency;

    public ReferralService(ReferralCodeRepository codes, ReferralConversionRepository conversions,
            com.bss.campaign.repository.CommunityGoalRepository goals,
            DomainEventPublisher events, TenantScope tenantScope, PartyScope partyScope,
            @Value("${bss.campaign.referral-reward-gb:5}") BigDecimal rewardGb,
            @Value("${bss.campaign.referral-velocity-per-day:5}") int velocityPerDay,
            @Value("${bss.campaign.club-share-amount:10}") BigDecimal clubShareAmount,
            @Value("${bss.campaign.club-share-currency:EUR}") String clubShareCurrency) {
        this.clubShareAmount = clubShareAmount;
        this.clubShareCurrency = clubShareCurrency;
        this.codes = codes;
        this.conversions = conversions;
        this.goals = goals;
        this.events = events;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.rewardGb = rewardGb;
        this.velocityPerDay = velocityPerDay;
    }

    /** The caller's own code — minted on first ask, theirs from then on. */
    @Transactional
    public Map<String, Object> myCode() {
        String tenant = tenantScope.currentTenantId();
        String party = requireSelf();
        ReferralCode code = codes.findByTenantIdAndReferrerPartyId(tenant, party)
                .orElseGet(() -> {
                    ReferralCode fresh = new ReferralCode();
                    fresh.setTenantId(tenant);
                    fresh.setCode(mint(tenant));
                    fresh.setReferrerPartyId(party);
                    fresh.setCreatedAt(OffsetDateTime.now());
                    return codes.save(fresh);
                });
        List<ReferralConversion> mine = conversions.findByTenantIdAndCode(tenant, code.getCode());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code.getCode());
        out.put("rewardGb", rewardGb);
        out.put("joined", mine.size());
        out.put("rewarded", mine.stream().filter(c -> ReferralConversion.REWARDED.equals(c.getStatus())).count());
        out.put("pending", mine.stream().filter(c -> ReferralConversion.PENDING.equals(c.getStatus())).count());
        return out;
    }

    /** A joiner redeems a code — once, never their own. Pays on first order. */
    @Transactional
    public Map<String, Object> redeem(String rawCode) {
        return redeem(rawCode, null);
    }

    @Transactional
    public Map<String, Object> redeem(String rawCode, String areaCode) {
        String tenant = tenantScope.currentTenantId();
        String joiner = requireSelf();
        String normalized = rawCode == null ? "" : rawCode.trim().toUpperCase();
        ReferralCode code = codes.findByTenantIdAndCode(tenant, normalized)
                .orElseThrow(() -> new NotFoundException("no such referral code"));
        if (code.getReferrerPartyId().equals(joiner)) {
            throw new BadRequestException("you cannot refer yourself");
        }
        if (conversions.findByTenantIdAndJoinerPartyId(tenant, joiner).isPresent()) {
            throw new BadRequestException("a referral was already redeemed on this account");
        }
        ReferralConversion conversion = new ReferralConversion();
        conversion.setId(UUID.randomUUID().toString());
        conversion.setTenantId(tenant);
        conversion.setCode(code.getCode());
        conversion.setReferrerPartyId(code.getReferrerPartyId());
        conversion.setJoinerPartyId(joiner);
        conversion.setStatus(ReferralConversion.PENDING);
        conversion.setRewardGb(rewardGb);
        conversion.setCreatedAt(OffsetDateTime.now());
        if (areaCode != null && !areaCode.isBlank()) {
            conversion.setAreaCode(areaCode.trim());
        }
        conversions.save(conversion);
        return Map.of("code", code.getCode(), "status", conversion.getStatus(),
                "rewardGb", rewardGb,
                "note", "the reward lands for BOTH of you when your first order completes");
    }

    /** The event hook: a completed order turns a pending referral into GBs. */
    @Transactional
    public void onOrderCompleted(String tenant, String partyId) {
        conversions.findByTenantIdAndJoinerPartyId(tenant, partyId)
                .filter(c -> ReferralConversion.PENDING.equals(c.getStatus()))
                .ifPresent(c -> {
                    long lastDay = conversions.countByTenantIdAndReferrerPartyIdAndCreatedAtAfter(
                            tenant, c.getReferrerPartyId(), OffsetDateTime.now().minusDays(1));
                    if (lastDay > velocityPerDay) {
                        c.setStatus(ReferralConversion.HELD);
                        conversions.save(c);
                        log.info("referral HELD (velocity {}>{}/day): referrer {} joiner {}",
                                lastDay, velocityPerDay, c.getReferrerPartyId(), partyId);
                        return;
                    }
                    c.setStatus(ReferralConversion.REWARDED);
                    c.setRewardedAt(OffsetDateTime.now());
                    conversions.save(c);
                    publishReward(tenant, c.getJoinerPartyId(), c.getId() + ":joiner");
                    publishReward(tenant, c.getReferrerPartyId(), c.getId() + ":referrer");
                    // H2 — the dugnad becomes money: a club-linked code accrues
                    // the club's share as a REAL liability in the subledger
                    codes.findByTenantIdAndCode(tenant, c.getCode())
                            .map(ReferralCode::getClubOrgId)
                            .filter(club -> club != null && !club.isBlank())
                            .ifPresent(club -> events.publish("ClubShareAccruedEvent", "clubShare",
                                    Map.of("conversionId", c.getId(), "clubOrgId", club,
                                            "amount", clubShareAmount,
                                            "currency", clubShareCurrency), tenant));
                    log.info("referral REWARDED: {} GB each — referrer {} joiner {}",
                            rewardGb, c.getReferrerPartyId(), partyId);
                });
    }

    private void publishReward(String tenant, String partyId, String rewardId) {
        events.publish("ReferralDataRewardEvent", "referralReward", Map.of(
                "partyId", partyId, "gb", rewardGb, "rewardId", rewardId), tenant);
    }

    /** The staff readout: every code, its conversions, the program's GB cost. */
    @Transactional(readOnly = true)
    public Map<String, Object> report() {
        String tenant = tenantScope.currentTenantId();
        List<ReferralConversion> all = conversions.findByTenantId(tenant);
        long rewarded = all.stream().filter(c -> ReferralConversion.REWARDED.equals(c.getStatus())).count();
        long held = all.stream().filter(c -> ReferralConversion.HELD.equals(c.getStatus())).count();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReferralConversion c : all) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c.getCode());
            row.put("referrerPartyId", c.getReferrerPartyId());
            row.put("joinerPartyId", c.getJoinerPartyId());
            row.put("status", c.getStatus());
            row.put("createdAt", c.getCreatedAt());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "ReferralReport");
        out.put("conversions", all.size());
        out.put("rewarded", rewarded);
        out.put("pending", all.size() - rewarded - held);
        out.put("held", held);
        // the honest cost line: what the program has PAID, in data
        out.put("rewardCostGb", rewardGb.multiply(BigDecimal.valueOf(rewarded * 2)));
        out.put("rows", rows);
        return out;
    }

    /** G3 — tie MY code to my local club: every conversion counts for them. */
    @Transactional
    public Map<String, Object> linkClub(String clubOrgId) {
        String tenant = tenantScope.currentTenantId();
        String party = requireSelf();
        ReferralCode code = codes.findByTenantIdAndReferrerPartyId(tenant, party)
                .orElseThrow(() -> new NotFoundException("mint your code first (GET /referral/myCode)"));
        code.setClubOrgId(clubOrgId == null || clubOrgId.isBlank() ? null : clubOrgId.trim());
        codes.save(code);
        return Map.of("code", code.getCode(), "clubOrgId",
                code.getClubOrgId() == null ? "" : code.getClubOrgId());
    }

    /** G3 — the street's game: create a goal (staff) and read its public score. */
    @Transactional
    public Map<String, Object> createGoal(Map<String, Object> dto) {
        if (dto.get("name") == null || dto.get("areaCode") == null || dto.get("target") == null) {
            throw new BadRequestException("name, areaCode and target are required");
        }
        com.bss.campaign.entity.CommunityGoal goal = new com.bss.campaign.entity.CommunityGoal();
        goal.setId(UUID.randomUUID().toString());
        goal.setTenantId(tenantScope.currentTenantId());
        goal.setName(String.valueOf(dto.get("name")));
        goal.setAreaCode(String.valueOf(dto.get("areaCode")).trim());
        goal.setTarget(Integer.parseInt(String.valueOf(dto.get("target"))));
        goal.setCreatedAt(OffsetDateTime.now());
        goals.save(goal);
        return progressOf(goal);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listGoals() {
        return goals.findByTenantId(tenantScope.currentTenantId())
                .stream().map(this::progressOf).toList();
    }

    /** The PUBLIC face: joined / target / percent — a score, never a person. */
    @Transactional(readOnly = true)
    public Map<String, Object> progress(String goalId) {
        com.bss.campaign.entity.CommunityGoal goal = goals
                .findByIdAndTenantId(goalId, tenantScope.currentTenantId())
                .orElseThrow(() -> new NotFoundException("no such community goal"));
        return progressOf(goal);
    }

    private Map<String, Object> progressOf(com.bss.campaign.entity.CommunityGoal goal) {
        long joined = conversions.countByTenantIdAndAreaCode(goal.getTenantId(), goal.getAreaCode());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", goal.getId());
        out.put("name", goal.getName());
        out.put("areaCode", goal.getAreaCode());
        out.put("target", goal.getTarget());
        out.put("joined", joined);
        out.put("percent", goal.getTarget() == 0 ? 0
                : Math.min(100, Math.round(joined * 100.0 / goal.getTarget())));
        out.put("unlocked", joined >= goal.getTarget());
        return out;
    }

    /** G3 — Klubbdugnad: every club's season tally, from its codes' conversions. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> clubReport() {
        String tenant = tenantScope.currentTenantId();
        Map<String, Long> joinedByClub = new LinkedHashMap<>();
        Map<String, Long> rewardedByClub = new LinkedHashMap<>();
        for (ReferralConversion c : conversions.findByTenantId(tenant)) {
            codes.findByTenantIdAndCode(tenant, c.getCode())
                    .map(ReferralCode::getClubOrgId)
                    .filter(club -> club != null && !club.isBlank())
                    .ifPresent(club -> {
                        joinedByClub.merge(club, 1L, Long::sum);
                        if (ReferralConversion.REWARDED.equals(c.getStatus())) {
                            rewardedByClub.merge(club, 1L, Long::sum);
                        }
                    });
        }
        List<Map<String, Object>> out = new ArrayList<>();
        joinedByClub.forEach((club, joined) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("clubOrgId", club);
            row.put("joined", joined);
            row.put("rewarded", rewardedByClub.getOrDefault(club, 0L));
            out.add(row);
        });
        out.sort((a, b) -> Long.compare((long) b.get("joined"), (long) a.get("joined")));
        return out;
    }

    private String requireSelf() {
        return partyScope.scopedPartyId()
                .orElseThrow(() -> new BadRequestException("a customer token is required"));
    }

    private String mint(String tenant) {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            if (codes.findByTenantIdAndCode(tenant, sb.toString()).isEmpty()) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("could not mint a unique referral code");
    }
}
