package com.bss.campaign.service;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.client.CommunicationClient;
import com.bss.campaign.client.SegmentMember;
import com.bss.campaign.dto.ArmSpec;
import com.bss.campaign.dto.CampaignExecutionView;
import com.bss.campaign.dto.CampaignPatch;
import com.bss.campaign.dto.CampaignRequest;
import com.bss.campaign.dto.CampaignStats;
import com.bss.campaign.dto.CampaignView;
import com.bss.campaign.dto.Conversions;
import com.bss.campaign.dto.ExecutionReceipt;
import com.bss.campaign.entity.Campaign;
import com.bss.campaign.entity.CampaignExecution;
import com.bss.campaign.events.DomainEventPublisher;
import com.bss.campaign.exception.BadRequestException;
import com.bss.campaign.exception.NotFoundException;
import com.bss.campaign.repository.CampaignExecutionRepository;
import com.bss.campaign.repository.CampaignRepository;
import com.bss.campaign.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The martech engine, first iteration: a campaign is a business-event
 * trigger, a message, and optionally a promotion code to hand out. The
 * event stream drives it; TMF681 delivers it; the unique execution row
 * guarantees a customer is reached at most once per campaign, whatever
 * at-least-once delivery does upstream.
 */
@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);
    // Full lifecycle; only ACTIVE triggers/executes. archived = soft delete.
    private static final String ARCHIVED = "archived";
    // declaration order, so the refusal names the states the same way on every JVM
    private static final Set<String> STATUSES = java.util.Collections.unmodifiableSet(
            new java.util.LinkedHashSet<>(List.of("draft", "scheduled", Campaign.ACTIVE, Campaign.PAUSED, ARCHIVED)));

    private static final com.fasterxml.jackson.core.type.TypeReference<List<ArmSpec>>
            ARM_LIST = new com.fasterxml.jackson.core.type.TypeReference<>() { };

    private final CampaignRepository campaigns;
    private final CampaignExecutionRepository executions;
    private final CommunicationClient communication;
    private final com.bss.campaign.client.InsightClient insight;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final FrequencyGuard frequency;
    private final com.bss.campaign.client.CatalogClient catalog;
    private final com.bss.campaign.decision.DecisionPoints decisions;

    public CampaignService(CampaignRepository campaigns, CampaignExecutionRepository executions,
            CommunicationClient communication, DomainEventPublisher events, TenantScope tenantScope,
            com.bss.campaign.client.InsightClient insight,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper, FrequencyGuard frequency,
            com.bss.campaign.client.CatalogClient catalog,
            com.bss.campaign.decision.DecisionPoints decisions) {
        this.decisions = decisions;
        this.campaigns = campaigns;
        this.executions = executions;
        this.communication = communication;
        this.insight = insight;
        this.events = events;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
        this.frequency = frequency;
        this.catalog = catalog;
    }

    @Transactional
    public CampaignView create(CampaignRequest dto) {
        List<ArmSpec> arms = parseArms(dto.messageVariants());
        String subject = dto.message() != null ? dto.message().subject() : arms != null ? arms.get(0).subject() : null;
        String content = dto.message() != null ? dto.message().content() : arms != null ? arms.get(0).content() : null;
        if (dto.name() == null
                || (dto.triggerEventType() == null && dto.segmentName() == null && dto.audienceRef() == null)
                || subject == null || content == null) {
            throw new BadRequestException(
                    "name, message {subject, content} (or messageVariants) and a trigger"
                    + " (triggerEventType, segmentName or audienceRef) are required");
        }
        Campaign entity = new Campaign();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/campaign/" + id);
        entity.setName(dto.name());
        entity.setStatus(dto.status() == null ? Campaign.ACTIVE : requireStatus(dto.status()));
        entity.setTriggerEventType(dto.triggerEventType());
        entity.setSegmentName(dto.segmentName());
        entity.setAudienceRef(dto.audienceRef());
        entity.setTriggerState(dto.triggerState());
        entity.setMessageSubject(subject);
        entity.setMessageContent(content);
        entity.setPromotionCode(dto.promotionCode());
        entity.setConversionEvent(dto.conversionEvent());
        if (dto.conversionWindowDays() != null) {
            entity.setConversionWindowDays(dto.conversionWindowDays());
        }
        if (dto.holdoutPercent() != null) {
            int holdout = dto.holdoutPercent();
            if (holdout < 0 || holdout > 90) {
                throw new BadRequestException("holdoutPercent must be 0-90");
            }
            entity.setHoldoutPercent(holdout);
        }
        if (arms != null) {
            try {
                entity.setArms(objectMapper.writeValueAsString(arms));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new BadRequestException("messageVariants could not be stored");
            }
        }
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return view(campaigns.save(entity));
    }

    @Transactional(readOnly = true)
    public List<CampaignView> findAll() {
        // archived campaigns are a soft delete: hidden from the default list
        return campaigns.findByTenantId(tenantScope.currentTenantId()).stream()
                .filter(c -> !ARCHIVED.equals(c.getStatus()))
                .map(this::view).toList();
    }

    @Transactional
    public CampaignView patch(String id, CampaignPatch patch) {
        Campaign entity = campaigns.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Campaign", id));
        if (patch.status() != null) {
            entity.setStatus(requireStatus(patch.status()));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return view(campaigns.save(entity));
    }

    /** Delete a campaign and its execution ledger. */
    @Transactional
    public void delete(String id) {
        Campaign entity = campaigns.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Campaign", id));
        executions.deleteByTenantIdAndCampaignId(tenantScope.currentTenantId(), id);
        campaigns.delete(entity);
    }

    /**
     * The other half of measurement: when a customer's business event
     * matches a campaign's conversion event INSIDE the window, their open
     * execution converts — treated or holdout alike; lift is the gap.
     */
    private void recordConversions(String tenant, String eventType, String state, String partyId,
            java.util.List<String> offeringIds) {
        java.util.List<CampaignExecution> open =
                executions.findByTenantIdAndPartyIdAndConvertedAtIsNull(tenant, partyId);
        if (open.isEmpty()) {
            return;
        }
        java.math.BigDecimal value = null; // catalog is asked once, and only on a match
        for (CampaignExecution execution : open) {
            Campaign campaign = campaigns
                    .findByIdAndTenantId(execution.getCampaignId(), tenant).orElse(null);
            if (campaign == null) {
                continue;
            }
            String wanted = campaign.getConversionEvent() == null || campaign.getConversionEvent().isBlank()
                    ? "ProductOrderStateChangeEvent:completed" : campaign.getConversionEvent();
            String[] parts = wanted.split(":", 2);
            boolean matches = parts[0].equals(eventType)
                    && (parts.length < 2 || parts[1].equals(state));
            boolean inWindow = execution.getExecutedAt()
                    .plusDays(campaign.getConversionWindowDays()).isAfter(OffsetDateTime.now());
            if (matches && inWindow) {
                if (value == null) {
                    value = catalog.monthlyValueOf(tenant, offeringIds);
                }
                execution.setConvertedAt(OffsetDateTime.now());
                execution.setConversionRef(eventType);
                execution.setConversionValue(value);
                executions.save(execution);
                decisions.outcome(execution.getDecisionId(), "conversion", value);
                log.info("campaign '{}' conversion: party {} ({}) worth {}/month",
                        campaign.getName(), partyId, execution.getVariant(), value);
            }
        }
    }

    /** The readout: reached / held out / conversions per variant / LIFT. */
    @Transactional(readOnly = true)
    public CampaignStats statsOf(String campaignId) {
        Campaign campaign = campaigns.findByIdAndTenantId(campaignId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Campaign", campaignId));
        java.util.List<CampaignExecution> all =
                executions.findByTenantIdAndCampaignId(tenantScope.currentTenantId(), campaignId);
        long treated = all.stream().filter(e -> !"holdout".equals(e.getVariant())).count();
        long heldOut = all.size() - treated;
        long treatedConv = all.stream()
                .filter(e -> !"holdout".equals(e.getVariant()) && e.getConvertedAt() != null).count();
        long holdoutConv = all.stream()
                .filter(e -> "holdout".equals(e.getVariant()) && e.getConvertedAt() != null).count();
        Double treatedRate = treated == 0 ? null : (double) treatedConv / treated;
        Double holdoutRate = heldOut == 0 ? null : (double) holdoutConv / heldOut;
        // ATTRIBUTED REVENUE: the monthly money conversions carry, read per
        // EXPOSED customer (incrementality is per person reached, not per
        // converter) — the revenue lift is what one more treated customer
        // is worth versus leaving them alone
        java.math.BigDecimal treatedRevenue = revenueOf(all, false);
        java.math.BigDecimal holdoutRevenue = revenueOf(all, true);
        CampaignStats.Revenue revenue = null;
        if (treatedRevenue.signum() != 0 || holdoutRevenue.signum() != 0) {
            revenue = new CampaignStats.Revenue(treatedRevenue, holdoutRevenue,
                    treated > 0 ? perCustomer(treatedRevenue, treated) : null,
                    heldOut > 0 ? perCustomer(holdoutRevenue, heldOut) : null,
                    treated > 0 && heldOut > 0
                            ? perCustomer(treatedRevenue, treated).subtract(perCustomer(holdoutRevenue, heldOut))
                            : null,
                    "monthly recurring value of converting orders");
        }
        List<ArmSpec> arms = armsOf(campaign);
        return new CampaignStats(campaignId, treated, heldOut, new Conversions(treatedConv, holdoutConv),
                treatedRate == null ? null : Math.round(treatedRate * 1000) / 10.0,
                holdoutRate == null ? null : Math.round(holdoutRate * 1000) / 10.0,
                treatedRate == null || holdoutRate == null ? null
                        : Math.round((treatedRate - holdoutRate) * 1000) / 10.0,
                campaign.getConversionWindowDays(),
                heldOut > 0 && heldOut < 5
                        ? "holdout under 5 people — the lift is an anecdote, not a measurement" : null,
                revenue,
                arms == null ? null : armStats(arms, all));
    }

    private java.math.BigDecimal revenueOf(List<CampaignExecution> all, boolean holdout) {
        return all.stream()
                .filter(e -> holdout == "holdout".equals(e.getVariant()))
                .map(CampaignExecution::getConversionValue)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    private java.math.BigDecimal perCustomer(java.math.BigDecimal total, long customers) {
        return total.divide(java.math.BigDecimal.valueOf(customers), 2,
                java.math.RoundingMode.HALF_UP);
    }

    /**
     * The A/B readout: per-arm sent / conversions / rate, a leader, and an
     * honest verdict — a two-proportion z-test at 95% decides whether the
     * gap is a finding or noise (with tiny samples it is ALWAYS noise, and
     * the readout says so instead of crowning a winner).
     */
    private CampaignStats.ArmReadout armStats(List<ArmSpec> arms, List<CampaignExecution> all) {
        List<CampaignStats.ArmStat> rows = new java.util.ArrayList<>();
        for (ArmSpec arm : arms) {
            String name = String.valueOf(arm.name());
            long sent = all.stream().filter(e -> name.equals(e.getArm())).count();
            long conv = all.stream()
                    .filter(e -> name.equals(e.getArm()) && e.getConvertedAt() != null).count();
            rows.add(new CampaignStats.ArmStat(name, arm.subject(), sent, conv,
                    sent == 0 ? null : Math.round((double) conv / sent * 1000) / 10.0));
        }
        CampaignStats.ArmStat best = rows.stream()
                .filter(r -> r.rate() != null)
                .max(java.util.Comparator
                        .comparingDouble((CampaignStats.ArmStat r) -> r.rate())
                        .thenComparingLong(CampaignStats.ArmStat::conversions))
                .orElse(null);
        String leader = null;
        String verdict = null;
        if (best != null && rows.size() >= 2) {
            List<CampaignStats.ArmStat> sorted = rows.stream()
                    .filter(r -> r.rate() != null)
                    .sorted(java.util.Comparator.comparingDouble((CampaignStats.ArmStat r) -> r.rate()).reversed())
                    .toList();
            leader = best.name();
            if (sorted.size() >= 2) {
                verdict = verdictOf(sorted.get(0).sent(), sorted.get(0).conversions(),
                        sorted.get(1).sent(), sorted.get(1).conversions(), best.name());
            }
        }
        return new CampaignStats.ArmReadout(rows, leader, verdict);
    }

    private String verdictOf(long n1, long c1, long n2, long c2, String leader) {
        if (n1 < 30 || n2 < 30) {
            return "arms under 30 people — the split is an anecdote, keep the test running";
        }
        double p1 = (double) c1 / n1, p2 = (double) c2 / n2;
        double pooled = (double) (c1 + c2) / (n1 + n2);
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2));
        if (se == 0) {
            return "no conversions yet — nothing to compare";
        }
        double z = (p1 - p2) / se;
        return Math.abs(z) >= 1.96
                ? "arm " + leader + " wins at 95% confidence"
                : "the gap is inside the noise (not significant at 95%) — keep the test running";
    }

    /**
     * SEGMENT BLAST: reach every consented, stitched customer the insight
     * component puts in the segment — once. Re-executing is idempotent (the
     * per-party execution row dedupes), so a growing segment can be swept
     * again and only the newcomers hear it.
     */
    @Transactional
    public ExecutionReceipt executeSegment(String campaignId) {
        Campaign campaign = campaigns.findByIdAndTenantId(campaignId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Campaign", campaignId));
        boolean hasAudience = campaign.getAudienceRef() != null && !campaign.getAudienceRef().isBlank();
        if ((campaign.getSegmentName() == null || campaign.getSegmentName().isBlank()) && !hasAudience) {
            throw new BadRequestException("this campaign has no segment or audience — it runs on events");
        }
        if (!Campaign.ACTIVE.equals(campaign.getStatus())) {
            throw new BadRequestException("only an active campaign can be executed");
        }
        // a saved Audience (rule tree) takes precedence over the bare segment string
        List<SegmentMember> members = hasAudience
                ? insight.audienceMembers(campaign.getAudienceRef())
                : insight.segmentMembers(campaign.getSegmentName());
        int reached = 0;
        for (SegmentMember member : members) {
            if (reach(campaign, String.valueOf(member.partyId()))) {
                reached++;
            }
        }
        return new ExecutionReceipt(campaignId,
                hasAudience ? campaign.getAudienceRef() : campaign.getSegmentName(), reached);
    }

    /** One customer, once: the shared delivery step for events and blasts.
     * A deterministic N% land in the HOLDOUT — same ledger, no message —
     * so lift can be measured instead of asserted. */
    private boolean reach(Campaign campaign, String partyId) {
        String tenant = tenantScope.currentTenantId();
        if (executions.existsByTenantIdAndCampaignIdAndPartyId(tenant, campaign.getId(), partyId)) {
            return false;
        }
        // quiet hours: the tenant is asleep — nobody is reached now; a
        // later execute (or the event stream tomorrow) picks them up
        if (frequency.quietUntil().isPresent()) {
            log.info("campaign '{}' silent: quiet hours until {}",
                    campaign.getName(), frequency.quietUntil().orElse(null));
            return false;
        }
        // frequency cap, checked BEFORE the variant hash so treated and
        // holdout are capped alike (no ledger row — a later execute may
        // reach them once their budget frees up)
        if (!frequency.canSend(partyId)) {
            log.info("campaign '{}' capped: party {} is at their marketing budget",
                    campaign.getName(), partyId);
            return false;
        }
        // THE DECISION: holdout, or which arm — through the seam, so the
        // record carries the eligible set, the policy and the propensity, and
        // the conversion later joins back by id. The guards above are the
        // constraints that ran first; they are named in the record.
        List<ArmSpec> arms = armsOf(campaign);
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add("holdout");
        if (arms == null || arms.isEmpty()) {
            candidates.add("message");
        } else {
            arms.forEach(a -> candidates.add(String.valueOf(a.name())));
        }
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("seed", campaign.getId());
        ctx.put("campaignId", campaign.getId());
        ctx.put("partyId", partyId);
        ctx.put("holdoutPercent", campaign.getHoldoutPercent());
        ctx.put("guards", List.of("once-per-customer", "quiet-hours", "frequency-cap"));
        com.bss.campaign.decision.DecisionRecord dealt = decisions.decide(
                com.bss.campaign.decision.DecisionPoints.CAMPAIGN_TREATMENT, partyId, ctx, candidates,
                List.of(), "holdout");
        boolean holdout = "holdout".equals(dealt.action());
        ArmSpec arm = null;
        if (!holdout && arms != null) {
            arm = arms.stream().filter(a -> dealt.action().equals(String.valueOf(a.name())))
                    .findFirst().orElse(null);
        }
        CampaignExecution execution = new CampaignExecution();
        execution.setId(UUID.randomUUID().toString());
        execution.setTenantId(tenant);
        execution.setCampaignId(campaign.getId());
        execution.setPartyId(partyId);
        execution.setVariant(holdout ? "holdout" : "treated");
        execution.setDecisionId(dealt.decisionId());
        if (arm != null) {
            execution.setArm(String.valueOf(arm.name()));
        }
        execution.setExecutedAt(OffsetDateTime.now());
        try {
            executions.save(execution);
        } catch (DataIntegrityViolationException e) {
            return false; // concurrent duplicate delivery lost the race — fine
        }
        if (!holdout) {
            String subject = arm != null ? String.valueOf(arm.subject())
                    : campaign.getMessageSubject();
            String body = arm != null ? String.valueOf(arm.content())
                    : campaign.getMessageContent();
            String content = campaign.getPromotionCode() == null
                    ? body : body.replace("{code}", campaign.getPromotionCode());
            CommunicationClient.SendOutcome outcome = communication.send(partyId, subject, content,
                    campaign.getName() == null ? null : Map.of("source", campaign.getName()));
            if (outcome == CommunicationClient.SendOutcome.CAPPED
                    || outcome == CommunicationClient.SendOutcome.SUPPRESSED) {
                // still ledgered as treated (they were TARGETED — holdout math
                // must not shift), but no touch is spent and the miss is loud
                log.warn("campaign '{}' message to party {} was {} by communication — "
                        + "ledgered as treated, nothing landed", campaign.getName(), partyId, outcome);
            } else {
                frequency.record(partyId, "campaign");
            }
        }
        events.publish("CampaignExecutionCreateEvent", "campaignExecution", Map.of(
                "campaignId", campaign.getId(), "partyId", partyId, "variant", execution.getVariant()));
        log.info("campaign '{}' {} party {}", campaign.getName(),
                holdout ? "held out" : "reached", partyId);
        return true;
    }

    @Transactional(readOnly = true)
    public List<CampaignExecutionView> executionsOf(String campaignId) {
        campaigns.findByIdAndTenantId(campaignId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Campaign", campaignId));
        return executions.findByTenantIdAndCampaignId(tenantScope.currentTenantId(), campaignId)
                .stream().map(e -> new CampaignExecutionView(e.getId(),
                        new CampaignExecutionView.PartyRef(e.getPartyId()),
                        e.getExecutedAt().toString(), "CampaignExecution"))
                .toList();
    }

    /**
     * The engine tick: a business event arrived for this tenant. Every
     * active campaign triggered by it reaches the event's customer once.
     */
    @Transactional
    public void onEvent(String eventType, String state, String partyId,
            java.util.List<String> offeringIds) {
        String tenant = tenantScope.currentTenantId();
        recordConversions(tenant, eventType, state, partyId, offeringIds);
        for (Campaign campaign : campaigns.findByTenantIdAndStatusAndTriggerEventType(
                tenant, Campaign.ACTIVE, eventType)) {
            if (campaign.getTriggerState() != null && !campaign.getTriggerState().equals(state)) {
                continue;
            }
            reach(campaign, partyId);
        }
    }

    /** A/B arms: 2-4 variants, each a complete message with a name. */
    private List<ArmSpec> parseArms(List<ArmSpec> raw) {
        if (raw == null) {
            return null;
        }
        if (raw.size() < 2 || raw.size() > 4) {
            throw new BadRequestException("messageVariants must be a list of 2-4 arms");
        }
        List<ArmSpec> arms = new java.util.ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (ArmSpec arm : raw) {
            if (arm == null || arm.name() == null || arm.subject() == null || arm.content() == null) {
                throw new BadRequestException("every arm needs name, subject and content");
            }
            if (!names.add(arm.name())) {
                throw new BadRequestException("arm names must be unique");
            }
            arms.add(new ArmSpec(arm.name(), arm.subject(), arm.content()));
        }
        return arms;
    }

    private List<ArmSpec> armsOf(Campaign campaign) {
        if (campaign.getArms() == null || campaign.getArms().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(campaign.getArms(), ARM_LIST);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("campaign '{}' has unreadable arms — falling back to the base message",
                    campaign.getName());
            return null;
        }
    }

    private String requireStatus(Object status) {
        String value = String.valueOf(status);
        if (!STATUSES.contains(value)) {
            throw new BadRequestException("status must be one of " + STATUSES);
        }
        return value;
    }

    private CampaignView view(Campaign c) {
        return new CampaignView(c.getId(), c.getHref(), c.getName(), c.getStatus(), c.getTriggerEventType(),
                c.getTriggerState(), new CampaignView.Message(c.getMessageSubject(), c.getMessageContent()),
                c.getPromotionCode(), c.getSegmentName(), c.getAudienceRef(), armsOf(c), c.getHoldoutPercent(),
                c.getConversionWindowDays(), c.getConversionEvent(), "Campaign");
    }
}
