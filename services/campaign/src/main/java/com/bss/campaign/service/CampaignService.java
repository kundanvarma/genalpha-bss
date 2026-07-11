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

    private final CampaignRepository campaigns;
    private final CampaignExecutionRepository executions;
    private final CommunicationClient communication;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;

    public CampaignService(CampaignRepository campaigns, CampaignExecutionRepository executions,
            CommunicationClient communication, DomainEventPublisher events, TenantScope tenantScope) {
        this.campaigns = campaigns;
        this.executions = executions;
        this.communication = communication;
        this.events = events;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null || dto.get("triggerEventType") == null
                || !(dto.get("message") instanceof Map<?, ?> message)
                || message.get("subject") == null || message.get("content") == null) {
            throw new BadRequestException(
                    "name, triggerEventType and message {subject, content} are required");
        }
        Campaign entity = new Campaign();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/campaign/" + id);
        entity.setName(String.valueOf(dto.get("name")));
        entity.setStatus(dto.get("status") == null ? Campaign.ACTIVE : requireStatus(dto.get("status")));
        entity.setTriggerEventType(String.valueOf(dto.get("triggerEventType")));
        entity.setTriggerState(dto.get("triggerState") == null ? null : String.valueOf(dto.get("triggerState")));
        entity.setMessageSubject(String.valueOf(message.get("subject")));
        entity.setMessageContent(String.valueOf(message.get("content")));
        entity.setPromotionCode(dto.get("promotionCode") == null ? null
                : String.valueOf(dto.get("promotionCode")));
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
        for (Campaign campaign : campaigns.findByTenantIdAndStatusAndTriggerEventType(
                tenant, Campaign.ACTIVE, eventType)) {
            if (campaign.getTriggerState() != null && !campaign.getTriggerState().equals(state)) {
                continue;
            }
            if (executions.existsByTenantIdAndCampaignIdAndPartyId(tenant, campaign.getId(), partyId)) {
                continue;
            }
            CampaignExecution execution = new CampaignExecution();
            execution.setId(UUID.randomUUID().toString());
            execution.setTenantId(tenant);
            execution.setCampaignId(campaign.getId());
            execution.setPartyId(partyId);
            execution.setExecutedAt(OffsetDateTime.now());
            try {
                executions.save(execution);
            } catch (DataIntegrityViolationException e) {
                continue; // concurrent duplicate delivery lost the race — fine
            }
            String content = campaign.getPromotionCode() == null
                    ? campaign.getMessageContent()
                    : campaign.getMessageContent().replace("{code}", campaign.getPromotionCode());
            communication.send(partyId, campaign.getMessageSubject(), content);
            events.publish("CampaignExecutionCreateEvent", "campaignExecution", Map.of(
                    "campaignId", campaign.getId(), "partyId", partyId));
            log.info("campaign '{}' reached party {} on {}", campaign.getName(), partyId, eventType);
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
        map.put("@type", "Campaign");
        return map;
    }
}
