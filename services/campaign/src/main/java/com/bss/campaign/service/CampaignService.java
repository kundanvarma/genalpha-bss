package com.bss.campaign.service;

import com.bss.campaign.api.ApiConstants;
import com.bss.campaign.client.CommunicationClient;
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
import java.util.LinkedHashMap;
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
    private static final Set<String> STATUSES = Set.of(Campaign.ACTIVE, Campaign.PAUSED);

    private static final com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>
            ARM_LIST = new com.fasterxml.jackson.core.type.TypeReference<>() { };

    private final CampaignRepository campaigns;
    private final CampaignExecutionRepository executions;
    private final CommunicationClient communication;
    private final com.bss.campaign.client.InsightClient insight;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public CampaignService(CampaignRepository campaigns, CampaignExecutionRepository executions,
            CommunicationClient communication, DomainEventPublisher events, TenantScope tenantScope,
            com.bss.campaign.client.InsightClient insight,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.campaigns = campaigns;
        this.executions = executions;
        this.communication = communication;
        this.insight = insight;
        this.events = events;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        List<Map<String, Object>> arms = parseArms(dto.get("messageVariants"));
        Map<?, ?> message = dto.get("message") instanceof Map<?, ?> m ? m
                : arms != null ? arms.get(0) : null;
        if (dto.get("name") == null
                || (dto.get("triggerEventType") == null && dto.get("segmentName") == null)
                || message == null
                || message.get("subject") == null || message.get("content") == null) {
            throw new BadRequestException(
                    "name, message {subject, content} (or messageVariants) and a trigger"
                    + " (triggerEventType or segmentName) are required");
        }
        Campaign entity = new Campaign();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/campaign/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setStatus(dto.get("status") == null ? Campaign.ACTIVE : requireStatus(dto.get("status")));
        entity.setTriggerEventType(dto.get("triggerEventType") == null ? null
                : String.valueOf(dto.get("triggerEventType")));
        entity.setSegmentName(dto.get("segmentName") == null ? null
                : String.valueOf(dto.get("segmentName")));
        entity.setTriggerState(dto.get("triggerState") == null ? null : String.valueOf(dto.get("triggerState")));
        entity.setMessageSubject(String.valueOf(message.get("subject")));
        entity.setMessageContent(String.valueOf(message.get("content")));
        entity.setPromotionCode(dto.get("promotionCode") == null ? null
                : String.valueOf(dto.get("promotionCode")));
        entity.setConversionEvent(dto.get("conversionEvent") == null ? null
                : String.valueOf(dto.get("conversionEvent")));
        if (dto.get("conversionWindowDays") != null) {
            entity.setConversionWindowDays(Integer.parseInt(String.valueOf(dto.get("conversionWindowDays"))));
        }
        if (dto.get("holdoutPercent") != null) {
            int holdout = Integer.parseInt(String.valueOf(dto.get("holdoutPercent")));
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
        return toMap(campaigns.save(entity));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll() {
        return campaigns.findByTenantId(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Campaign entity = campaigns.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Campaign", id));
        if (patch.get("status") != null) {
            entity.setStatus(requireStatus(patch.get("status")));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(campaigns.save(entity));
    }

    /**
     * The other half of measurement: when a customer's business event
     * matches a campaign's conversion event INSIDE the window, their open
     * execution converts — treated or holdout alike; lift is the gap.
     */
    private void recordConversions(String tenant, String eventType, String state, String partyId) {
        java.util.List<CampaignExecution> open =
                executions.findByTenantIdAndPartyIdAndConvertedAtIsNull(tenant, partyId);
        if (open.isEmpty()) {
            return;
        }
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
                execution.setConvertedAt(OffsetDateTime.now());
                execution.setConversionRef(eventType);
                executions.save(execution);
                log.info("campaign '{}' conversion: party {} ({})",
                        campaign.getName(), partyId, execution.getVariant());
            }
        }
    }

    /** The readout: reached / held out / conversions per variant / LIFT. */
    @Transactional(readOnly = true)
    public Map<String, Object> statsOf(String campaignId) {
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
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("campaignId", campaignId);
        stats.put("reached", treated);
        stats.put("heldOut", heldOut);
        stats.put("conversions", Map.of("treated", treatedConv, "holdout", holdoutConv));
        if (treatedRate != null) {
            stats.put("treatedRate", Math.round(treatedRate * 1000) / 10.0);
        }
        if (holdoutRate != null) {
            stats.put("holdoutRate", Math.round(holdoutRate * 1000) / 10.0);
        }
        if (treatedRate != null && holdoutRate != null) {
            stats.put("liftPoints", Math.round((treatedRate - holdoutRate) * 1000) / 10.0);
        }
        stats.put("conversionWindowDays", campaign.getConversionWindowDays());
        if (heldOut > 0 && heldOut < 5) {
            stats.put("note", "holdout under 5 people — the lift is an anecdote, not a measurement");
        }
        List<Map<String, Object>> arms = armsOf(campaign);
        if (arms != null) {
            stats.put("arms", armStats(arms, all));
        }
        return stats;
    }

    /**
     * The A/B readout: per-arm sent / conversions / rate, a leader, and an
     * honest verdict — a two-proportion z-test at 95% decides whether the
     * gap is a finding or noise (with tiny samples it is ALWAYS noise, and
     * the readout says so instead of crowning a winner).
     */
    private Map<String, Object> armStats(List<Map<String, Object>> arms,
            List<CampaignExecution> all) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> arm : arms) {
            String name = String.valueOf(arm.get("name"));
            long sent = all.stream().filter(e -> name.equals(e.getArm())).count();
            long conv = all.stream()
                    .filter(e -> name.equals(e.getArm()) && e.getConvertedAt() != null).count();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("subject", arm.get("subject"));
            row.put("sent", sent);
            row.put("conversions", conv);
            row.put("rate", sent == 0 ? null : Math.round((double) conv / sent * 1000) / 10.0);
            rows.add(row);
        }
        Map<String, Object> best = rows.stream()
                .filter(r -> r.get("rate") != null)
                .max(java.util.Comparator
                        .comparingDouble((Map<String, Object> r) -> (Double) r.get("rate"))
                        .thenComparingLong(r -> (Long) r.get("conversions")))
                .orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arms", rows);
        if (best != null && rows.size() >= 2) {
            List<Map<String, Object>> sorted = rows.stream()
                    .filter(r -> r.get("rate") != null)
                    .sorted(java.util.Comparator.comparingDouble(
                            (Map<String, Object> r) -> (Double) r.get("rate")).reversed())
                    .toList();
            out.put("leader", best.get("name"));
            if (sorted.size() >= 2) {
                long n1 = (Long) sorted.get(0).get("sent"), n2 = (Long) sorted.get(1).get("sent");
                long c1 = (Long) sorted.get(0).get("conversions"),
                        c2 = (Long) sorted.get(1).get("conversions");
                out.put("verdict", verdictOf(n1, c1, n2, c2, String.valueOf(best.get("name"))));
            }
        }
        return out;
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
    public Map<String, Object> executeSegment(String campaignId) {
        Campaign campaign = campaigns.findByIdAndTenantId(campaignId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Campaign", campaignId));
        if (campaign.getSegmentName() == null || campaign.getSegmentName().isBlank()) {
            throw new BadRequestException("this campaign has no segment — it runs on events");
        }
        if (!Campaign.ACTIVE.equals(campaign.getStatus())) {
            throw new BadRequestException("only an active campaign can be executed");
        }
        int reached = 0;
        for (Map<String, Object> member : insight.segmentMembers(campaign.getSegmentName())) {
            if (reach(campaign, String.valueOf(member.get("partyId")))) {
                reached++;
            }
        }
        return Map.of("campaignId", campaignId, "segment", campaign.getSegmentName(),
                "reached", reached);
    }

    /** One customer, once: the shared delivery step for events and blasts.
     * A deterministic N% land in the HOLDOUT — same ledger, no message —
     * so lift can be measured instead of asserted. */
    private boolean reach(Campaign campaign, String partyId) {
        String tenant = tenantScope.currentTenantId();
        if (executions.existsByTenantIdAndCampaignIdAndPartyId(tenant, campaign.getId(), partyId)) {
            return false;
        }
        boolean holdout = campaign.getHoldoutPercent() > 0
                && Math.floorMod((campaign.getId() + partyId).hashCode(), 100) < campaign.getHoldoutPercent();
        List<Map<String, Object>> arms = armsOf(campaign);
        Map<String, Object> arm = holdout || arms == null ? null
                // a DIFFERENT hash than the holdout bucket, so arms split the
                // treated group evenly instead of mirroring the holdout edge
                : arms.get(Math.floorMod((campaign.getId() + ":arm:" + partyId).hashCode(),
                        arms.size()));
        CampaignExecution execution = new CampaignExecution();
        execution.setId(UUID.randomUUID().toString());
        execution.setTenantId(tenant);
        execution.setCampaignId(campaign.getId());
        execution.setPartyId(partyId);
        execution.setVariant(holdout ? "holdout" : "treated");
        if (arm != null) {
            execution.setArm(String.valueOf(arm.get("name")));
        }
        execution.setExecutedAt(OffsetDateTime.now());
        try {
            executions.save(execution);
        } catch (DataIntegrityViolationException e) {
            return false; // concurrent duplicate delivery lost the race — fine
        }
        if (!holdout) {
            String subject = arm != null ? String.valueOf(arm.get("subject"))
                    : campaign.getMessageSubject();
            String body = arm != null ? String.valueOf(arm.get("content"))
                    : campaign.getMessageContent();
            String content = campaign.getPromotionCode() == null
                    ? body : body.replace("{code}", campaign.getPromotionCode());
            communication.send(partyId, subject, content);
        }
        events.publish("CampaignExecutionCreateEvent", "campaignExecution", Map.of(
                "campaignId", campaign.getId(), "partyId", partyId, "variant", execution.getVariant()));
        log.info("campaign '{}' {} party {}", campaign.getName(),
                holdout ? "held out" : "reached", partyId);
        return true;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> executionsOf(String campaignId) {
        campaigns.findByIdAndTenantId(campaignId, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Campaign", campaignId));
        return executions.findByTenantIdAndCampaignId(tenantScope.currentTenantId(), campaignId)
                .stream().map(e -> Map.<String, Object>of(
                        "id", e.getId(),
                        "party", Map.of("id", e.getPartyId()),
                        "executedAt", e.getExecutedAt().toString(),
                        "@type", "CampaignExecution"))
                .toList();
    }

    /**
     * The engine tick: a business event arrived for this tenant. Every
     * active campaign triggered by it reaches the event's customer once.
     */
    @Transactional
    public void onEvent(String eventType, String state, String partyId) {
        String tenant = tenantScope.currentTenantId();
        recordConversions(tenant, eventType, state, partyId);
        for (Campaign campaign : campaigns.findByTenantIdAndStatusAndTriggerEventType(
                tenant, Campaign.ACTIVE, eventType)) {
            if (campaign.getTriggerState() != null && !campaign.getTriggerState().equals(state)) {
                continue;
            }
            reach(campaign, partyId);
        }
    }

    /** A/B arms: 2-4 variants, each a complete message with a name. */
    private List<Map<String, Object>> parseArms(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List<?> list) || list.size() < 2 || list.size() > 4) {
            throw new BadRequestException("messageVariants must be a list of 2-4 arms");
        }
        List<Map<String, Object>> arms = new java.util.ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> arm) || arm.get("name") == null
                    || arm.get("subject") == null || arm.get("content") == null) {
                throw new BadRequestException("every arm needs name, subject and content");
            }
            if (!names.add(String.valueOf(arm.get("name")))) {
                throw new BadRequestException("arm names must be unique");
            }
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("name", String.valueOf(arm.get("name")));
            clean.put("subject", String.valueOf(arm.get("subject")));
            clean.put("content", String.valueOf(arm.get("content")));
            arms.add(clean);
        }
        return arms;
    }

    private List<Map<String, Object>> armsOf(Campaign campaign) {
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

    private Map<String, Object> toMap(Campaign c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("href", c.getHref());
        map.put("name", c.getName());
        map.put("status", c.getStatus());
        map.put("triggerEventType", c.getTriggerEventType());
        if (c.getTriggerState() != null) map.put("triggerState", c.getTriggerState());
        map.put("message", Map.of("subject", c.getMessageSubject(), "content", c.getMessageContent()));
        if (c.getPromotionCode() != null) map.put("promotionCode", c.getPromotionCode());
        if (c.getSegmentName() != null) map.put("segmentName", c.getSegmentName());
        List<Map<String, Object>> arms = armsOf(c);
        if (arms != null) map.put("messageVariants", arms);
        map.put("holdoutPercent", c.getHoldoutPercent());
        map.put("conversionWindowDays", c.getConversionWindowDays());
        if (c.getConversionEvent() != null) map.put("conversionEvent", c.getConversionEvent());
        map.put("@type", "Campaign");
        return map;
    }
}
