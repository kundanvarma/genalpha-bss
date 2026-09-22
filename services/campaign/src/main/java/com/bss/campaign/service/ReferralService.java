package com.bss.campaign.service;

import com.bss.campaign.dto.ClubLinkReceipt;
import com.bss.campaign.dto.ClubTally;
import com.bss.campaign.dto.CommunityGoalRequest;
import com.bss.campaign.dto.CommunityGoalView;
import com.bss.campaign.dto.RedeemReceipt;
import com.bss.campaign.dto.ReferralCodeView;
import com.bss.campaign.dto.ReferralReport;
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
    public ReferralCodeView myCode() {
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
        return new ReferralCodeView(code.getCode(), rewardGb, mine.size(),
                mine.stream().filter(c -> ReferralConversion.REWARDED.equals(c.getStatus())).count(),
                mine.stream().filter(c -> ReferralConversion.PENDING.equals(c.getStatus())).count());
    }

    /** A joiner redeems a code — once, never their own. Pays on first order. */
    @Transactional
    public RedeemReceipt redeem(String rawCode) {
        return redeem(rawCode, null);
    }

    @Transactional
    public RedeemReceipt redeem(String rawCode, String areaCode) {
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
        return new RedeemReceipt(code.getCode(), conversion.getStatus(), rewardGb,
                "the reward lands for BOTH of you when your first order completes");
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
    public ReferralReport report() {
        String tenant = tenantScope.currentTenantId();
        List<ReferralConversion> all = conversions.findByTenantId(tenant);
        long rewarded = all.stream().filter(c -> ReferralConversion.REWARDED.equals(c.getStatus())).count();
        long held = all.stream().filter(c -> ReferralConversion.HELD.equals(c.getStatus())).count();
        List<ReferralReport.Row> rows = new ArrayList<>();
        for (ReferralConversion c : all) {
            rows.add(new ReferralReport.Row(c.getCode(), c.getReferrerPartyId(), c.getJoinerPartyId(),
                    c.getStatus(), c.getCreatedAt()));
        }
        // the honest cost line: what the program has PAID, in data
        return new ReferralReport("ReferralReport", all.size(), rewarded, all.size() - rewarded - held, held,
                rewardGb.multiply(BigDecimal.valueOf(rewarded * 2)), rows);
    }

    /** G3 — tie MY code to my local club: every conversion counts for them. */
    @Transactional
    public ClubLinkReceipt linkClub(String clubOrgId) {
        String tenant = tenantScope.currentTenantId();
        String party = requireSelf();
        ReferralCode code = codes.findByTenantIdAndReferrerPartyId(tenant, party)
                .orElseThrow(() -> new NotFoundException("mint your code first (GET /referral/myCode)"));
        code.setClubOrgId(clubOrgId == null || clubOrgId.isBlank() ? null : clubOrgId.trim());
        codes.save(code);
        return new ClubLinkReceipt(code.getCode(), code.getClubOrgId() == null ? "" : code.getClubOrgId());
    }

    /** G3 — the street's game: create a goal (staff) and read its public score. */
    @Transactional
    public CommunityGoalView createGoal(CommunityGoalRequest dto) {
        if (dto.name() == null || dto.areaCode() == null || dto.target() == null) {
            throw new BadRequestException("name, areaCode and target are required");
        }
        com.bss.campaign.entity.CommunityGoal goal = new com.bss.campaign.entity.CommunityGoal();
        goal.setId(UUID.randomUUID().toString());
        goal.setTenantId(tenantScope.currentTenantId());
        goal.setName(dto.name());
        goal.setAreaCode(dto.areaCode().trim());
        goal.setTarget(dto.target());
        goal.setCreatedAt(OffsetDateTime.now());
        goals.save(goal);
        return progressOf(goal);
    }

    @Transactional(readOnly = true)
    public List<CommunityGoalView> listGoals() {
        return goals.findByTenantId(tenantScope.currentTenantId())
                .stream().map(this::progressOf).toList();
    }

    /** The PUBLIC face: joined / target / percent — a score, never a person. */
    @Transactional(readOnly = true)
    public CommunityGoalView progress(String goalId) {
        com.bss.campaign.entity.CommunityGoal goal = goals
                .findByIdAndTenantId(goalId, tenantScope.currentTenantId())
                .orElseThrow(() -> new NotFoundException("no such community goal"));
        return progressOf(goal);
    }

    private CommunityGoalView progressOf(com.bss.campaign.entity.CommunityGoal goal) {
        long joined = conversions.countByTenantIdAndAreaCode(goal.getTenantId(), goal.getAreaCode());
        long percent = goal.getTarget() == 0 ? 0
                : Math.min(100, Math.round(joined * 100.0 / goal.getTarget()));
        return new CommunityGoalView(goal.getId(), goal.getName(), goal.getAreaCode(), goal.getTarget(), joined,
                percent, joined >= goal.getTarget());
    }

    /** G3 — Klubbdugnad: every club's season tally, from its codes' conversions. */
    @Transactional(readOnly = true)
    public List<ClubTally> clubReport() {
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
        List<ClubTally> out = new ArrayList<>();
        joinedByClub.forEach((club, joined) -> out.add(new ClubTally(club, joined, rewardedByClub.getOrDefault(club, 0L))));
        out.sort((a, b) -> Long.compare(b.joined(), a.joined()));
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
