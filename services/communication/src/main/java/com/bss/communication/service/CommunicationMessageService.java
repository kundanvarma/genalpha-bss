package com.bss.communication.service;

import com.bss.communication.api.ApiConstants;
import com.bss.communication.api.OffsetPageRequest;
import com.bss.communication.api.PagedResult;
import com.bss.communication.entity.CommunicationMessage;
import com.bss.communication.events.DomainEventPublisher;
import com.bss.communication.exception.BadRequestException;
import com.bss.communication.exception.NotFoundException;
import com.bss.communication.notify.EventNotificationMapper;
import com.bss.communication.repository.CommunicationMessageRepository;
import com.bss.communication.security.PartyScope;
import com.bss.communication.security.TenantScope;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF681 messages: minted from the event stream, read (and marked read) by
 * their receiver. Ad-hoc sends are back-office/martech — customers receive,
 * they do not send.
 */
@Service
public class CommunicationMessageService {

    private static final String RESOURCE = "CommunicationMessage";

    private final CommunicationMessageRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final com.bss.communication.client.EspForwarder esp;
    private final com.bss.communication.client.ChannelDispatcher channels;
    private final MessageTemplateService templates;
    private final com.bss.communication.client.PartyLookupClient parties;

    private final com.bss.communication.repository.MarketingOptOutRepository optOuts;
    private final UnsubscribeToken unsub;
    private final int freqCapMax;
    private final int freqCapWindowHours;

    public CommunicationMessageService(CommunicationMessageRepository repository, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope, com.bss.communication.client.EspForwarder esp,
            com.bss.communication.client.ChannelDispatcher channels, MessageTemplateService templates,
            com.bss.communication.client.PartyLookupClient parties,
            com.bss.communication.repository.MarketingOptOutRepository optOuts, UnsubscribeToken unsub,
            @org.springframework.beans.factory.annotation.Value("${bss.communication.frequency-cap-max:0}") int freqCapMax,
            @org.springframework.beans.factory.annotation.Value("${bss.communication.frequency-cap-window-hours:24}") int freqCapWindowHours) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.esp = esp;
        this.channels = channels;
        this.templates = templates;
        this.parties = parties;
        this.optOuts = optOuts;
        this.unsub = unsub;
        this.freqCapMax = freqCapMax;
        this.freqCapWindowHours = freqCapWindowHours;
    }

    /** Consumer path: idempotent on the source event id (at-least-once upstream). */
    @Transactional
    public void mint(String sourceEventId, String sourceEventType, String envelopeTenantId,
            EventNotificationMapper.Notification n) {
        // The notification lives in the tenant that produced the event.
        // Pre-tenancy envelopes carry no tenantId; those land in the default
        // tenant (the Kafka consumer has no request context of its own).
        String tenantId = envelopeTenantId != null ? envelopeTenantId : tenantScope.currentTenantId();
        if (repository.existsByTenantIdAndSourceEventId(tenantId, sourceEventId)) {
            return;
        }
        CommunicationMessage entity = new CommunicationMessage();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setHref(ApiConstants.BASE_PATH + "/communicationMessage/" + id);
        entity.setSubject(n.subject());
        entity.setContent(n.content());
        entity.setMessageType("inApp");
        entity.setStatus(CommunicationMessage.SENT);
        entity.setReceiverPartyId(n.partyId());
        entity.setSourceEventId(sourceEventId);
        entity.setSourceEventType(sourceEventType);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        repository.save(entity);
        // minted notifications are customer touchpoints too — downstream
        // (the TMF683 timeline) hears about EVERY message, not only ad-hoc
        events.publish("CommunicationMessageCreateEvent", "communicationMessage", toMap(entity));
        esp.forward(tenantId, id, n.partyId(), n.subject(), n.content(),
                n.attachmentName(), n.attachmentBase64());
    }

    @Transactional(readOnly = true)
    public PagedResult<Map<String, Object>> findAll(int offset, int limit, Map<String, String> filters) {
        CommunicationMessage probe = new CommunicationMessage();
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "relatedPartyId" -> probe.setReceiverPartyId(f.getValue());
                default -> throw new BadRequestException("unsupported filter attribute '" + f.getKey() + "'");
            }
        }
        probe.setTenantId(tenantScope.currentTenantId());
        partyScope.scopedPartyId().ifPresent(probe::setReceiverPartyId);
        Page<CommunicationMessage> page = repository.findAll(Example.of(probe),
                new OffsetPageRequest(offset, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PagedResult<>(page.getContent().stream().map(this::toMap).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        CommunicationMessage entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toMap(entity);
    }

    /** Ad-hoc send — the martech door. Customers receive, they do not send. */
    @Transactional
    public Map<String, Object> send(Map<String, Object> dto) {
        if (partyScope.scopedPartyId().isPresent()) {
            throw new BadRequestException("customers receive messages; sending is back-office");
        }
        // A PROSPECT reach: a not-yet-customer addressed by raw email (no party).
        // Consent is enforced upstream (the prospect audience only yields
        // consented contacts); the suppression list still applies at the ESP.
        String toEmail = dto.get("toEmail") == null ? null : String.valueOf(dto.get("toEmail")).trim();
        if (toEmail != null && !toEmail.isBlank() && receiverIn(dto) == null) {
            return sendToProspect(toEmail, dto);
        }
        String target = receiverIn(dto);
        if (target == null) {
            throw new BadRequestException("subject and receiver (relatedParty role 'customer') are required");
        }
        String tenantId = tenantScope.currentTenantId();
        boolean templated = dto.get("templateRef") != null;
        // B2B: an Organization account fans out to its member Individuals — the
        // humans who read mail — so "notify the account" reaches a person. A B2C
        // individual is simply its own single recipient (unchanged behaviour).
        List<String> recipients = parties.recipientsOf(tenantId, target);
        Map<String, Object> firstCreated = null;
        int capped = 0;
        int optedOut = 0;
        for (String receiver : recipients) {
            // MARKETING OPT-OUT: the customer's own choice (preference centre or a
            // one-click unsubscribe) wins over any campaign — in-app AND email. This
            // is the martech door, so every send here is marketing and must honour it.
            if (optOuts.existsByTenantIdAndPartyId(tenantId, receiver)) {
                optedOut++;
                continue;
            }
            // FREQUENCY CAP: the martech door governs contact frequency — a party
            // over the cap in the window is skipped, so campaigns/journeys can't
            // over-message. Transactional mail (mint) never runs through here.
            if (freqCapMax > 0 && repository.countByTenantIdAndReceiverPartyIdAndCreatedAtAfter(
                    tenantId, receiver, OffsetDateTime.now().minusHours(freqCapWindowHours)) >= freqCapMax) {
                capped++;
                continue;
            }
            // Personalize per recipient: the contact's own name, plus the org
            // tokens ({{organization.name}}) resolved from the company they're on.
            Map<String, Object> rendered = templated
                    ? templates.materialize(receiver, dto)
                    : templates.renderInline(receiver, dto);
            if (rendered.get("subject") == null) {
                throw new BadRequestException("subject and receiver (relatedParty role 'customer') are required");
            }
            CommunicationMessage entity = new CommunicationMessage();
            String id = UUID.randomUUID().toString();
            entity.setId(id);
            entity.setTenantId(tenantId);
            entity.setHref(ApiConstants.BASE_PATH + "/communicationMessage/" + id);
            entity.setSubject(String.valueOf(rendered.get("subject")));
            // Unsubscribe in EVERY marketing message (the law + the honest thing) —
            // a one-click, no-login link keyed to this recipient.
            String body = rendered.get("content") == null ? "" : String.valueOf(rendered.get("content"));
            entity.setContent(body + "\n\n—\nToo many emails? Unsubscribe: " + unsub.linkFor(receiver));
            entity.setMessageType(rendered.get("messageType") == null ? "inApp" : String.valueOf(rendered.get("messageType")));
            entity.setStatus(CommunicationMessage.SENT);
            entity.setReceiverPartyId(receiver);
            entity.setSource(dto.get("source") == null ? null : String.valueOf(dto.get("source")));
            entity.setCreatedAt(OffsetDateTime.now());
            entity.setLastUpdate(OffsetDateTime.now());
            Map<String, Object> created = toMap(repository.save(entity));
            events.publish("CommunicationMessageCreateEvent", "communicationMessage", created);
            // route to the channel's delivery seam (email/sms/push); inApp is the inbox
            channels.dispatch(entity.getTenantId(), entity.getId(), receiver,
                    entity.getSubject(), entity.getContent(), entity.getMessageType());
            if (firstCreated == null) firstCreated = created;
        }
        if (firstCreated == null && (capped > 0 || optedOut > 0)) {
            return Map.of("status", optedOut > 0 && capped == 0 ? "suppressed" : "capped",
                    "capped", capped, "optedOut", optedOut,
                    "reason", optedOut > 0 && capped == 0
                            ? "every recipient has opted out of marketing"
                            : "frequency cap reached for all recipients in the window");
        }
        return firstCreated;
    }

    /** Reach a prospect by email — the not-yet-customer path. Email only (no
     * inbox/account); brand + event tokens still render. */
    private Map<String, Object> sendToProspect(String email, Map<String, Object> dto) {
        String tenantId = tenantScope.currentTenantId();
        Map<String, Object> rendered = templates.renderInline(null, dto);
        if (rendered.get("subject") == null) {
            throw new BadRequestException("subject and toEmail are required for a prospect reach");
        }
        CommunicationMessage entity = new CommunicationMessage();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setHref(ApiConstants.BASE_PATH + "/communicationMessage/" + id);
        entity.setSubject(String.valueOf(rendered.get("subject")));
        entity.setContent(rendered.get("content") == null ? null : String.valueOf(rendered.get("content")));
        entity.setMessageType("email");
        entity.setStatus(CommunicationMessage.SENT);
        entity.setReceiverPartyId("prospect:" + email);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity));
        events.publish("CommunicationMessageCreateEvent", "communicationMessage", created);
        esp.forwardToEmail(tenantId, id, email, entity.getSubject(), entity.getContent());
        return created;
    }

    /** The one legal change: the receiver marking their message read. */
    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        CommunicationMessage entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (!CommunicationMessage.READ.equals(patch.get("status"))) {
            throw new BadRequestException("the only supported change is status: 'read'");
        }
        entity.setStatus(CommunicationMessage.READ);
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    private String receiverIn(Map<String, Object> dto) {
        if (dto.get("relatedParty") instanceof List<?> parties) {
            for (Object p : parties) {
                if (p instanceof Map<?, ?> ref && "customer".equalsIgnoreCase(String.valueOf(ref.get("role")))
                        && ref.get("id") != null) {
                    return String.valueOf(ref.get("id"));
                }
            }
        }
        return null;
    }

    private void requireOwn(CommunicationMessage entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getReceiverPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private Map<String, Object> toMap(CommunicationMessage entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("href", entity.getHref());
        map.put("subject", entity.getSubject());
        map.put("content", entity.getContent());
        map.put("messageType", entity.getMessageType());
        map.put("status", entity.getStatus());
        if (entity.getSource() != null) {
            map.put("source", entity.getSource());
        }
        if (entity.getDeliveryStatus() != null) {
            map.put("deliveryStatus", entity.getDeliveryStatus());
        }
        map.put("relatedParty", List.of(Map.of(
                "id", entity.getReceiverPartyId(), "role", "customer", "@referredType", "Individual")));
        if (entity.getSourceEventType() != null) {
            map.put("characteristic", List.of(Map.of(
                    "name", "sourceEventType", "value", entity.getSourceEventType())));
        }
        map.put("sendTime", entity.getCreatedAt());
        map.put("lastUpdate", entity.getLastUpdate());
        map.put("@type", "CommunicationMessage");
        return map;
    }
}
