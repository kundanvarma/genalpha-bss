package com.bss.communication.service;

import com.bss.communication.api.ApiConstants;
import com.bss.communication.api.OffsetPageRequest;
import com.bss.communication.api.PagedResult;
import com.bss.communication.dto.MessagePatch;
import com.bss.communication.dto.MessageView;
import com.bss.communication.dto.NameValue;
import com.bss.communication.dto.PartyRef;
import com.bss.communication.dto.RenderedMessage;
import com.bss.communication.dto.SendOutcome;
import com.bss.communication.dto.SendRequest;
import com.bss.communication.dto.SuppressedSend;
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
    private final com.bss.communication.security.TenantRegistry registry;
    private final int freqCapMax;
    private final int freqCapWindowHours;

    public CommunicationMessageService(CommunicationMessageRepository repository, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope, com.bss.communication.client.EspForwarder esp,
            com.bss.communication.client.ChannelDispatcher channels, MessageTemplateService templates,
            com.bss.communication.client.PartyLookupClient parties,
            com.bss.communication.repository.MarketingOptOutRepository optOuts, UnsubscribeToken unsub,
            com.bss.communication.security.TenantRegistry registry,
            @org.springframework.beans.factory.annotation.Value("${bss.communication.frequency-cap-max:0}") int freqCapMax,
            @org.springframework.beans.factory.annotation.Value("${bss.communication.frequency-cap-window-hours:24}") int freqCapWindowHours) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.esp = esp;
        this.registry = registry;
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
        events.publish("CommunicationMessageCreateEvent", "communicationMessage", toView(entity));
        esp.forward(tenantId, id, n.partyId(), n.subject(), n.content(),
                n.attachmentName(), n.attachmentBase64());
    }

    @Transactional(readOnly = true)
    public PagedResult<MessageView> findAll(int offset, int limit, Map<String, String> filters) {
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
        return new PagedResult<>(page.getContent().stream().map(this::toView).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public MessageView findById(String id) {
        CommunicationMessage entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        return toView(entity);
    }

    /** Ad-hoc send — the martech door. Customers receive, they do not send. */
    @Transactional
    public SendOutcome send(SendRequest dto) {
        if (partyScope.scopedPartyId().isPresent()) {
            throw new BadRequestException("customers receive messages; sending is back-office");
        }
        // A PROSPECT reach: a not-yet-customer addressed by raw email (no party).
        // Consent is enforced upstream (the prospect audience only yields
        // consented contacts); the suppression list still applies at the ESP.
        String toEmail = dto.toEmail() == null ? null : dto.toEmail().trim();
        if (toEmail != null && !toEmail.isBlank() && dto.receiver() == null) {
            return sendToProspect(toEmail, dto);
        }
        String target = dto.receiver();
        if (target == null) {
            throw new BadRequestException("subject and receiver (relatedParty role 'customer') are required");
        }
        String tenantId = tenantScope.currentTenantId();
        boolean templated = dto.templateRef() != null;
        // B2B: an Organization account fans out to its member Individuals — the
        // humans who read mail — so "notify the account" reaches a person. A B2C
        // individual is simply its own single recipient (unchanged behaviour).
        List<String> recipients = parties.recipientsOf(tenantId, target);
        MessageView firstCreated = null;
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
            // over-message. Transactional mail (mint) never runs through here,
            // and it never counts against the marketing budget either: only
            // martech-door messages (sourceEventId null) are tallied, so a
            // fresh order's own receipts can't cap the welcome journey.
            if (freqCapMax > 0 && repository
                    .countByTenantIdAndReceiverPartyIdAndSourceEventIdIsNullAndCreatedAtAfter(
                    tenantId, receiver, OffsetDateTime.now().minusHours(freqCapWindowHours)) >= freqCapMax) {
                capped++;
                continue;
            }
            // Personalize per recipient: the contact's own name, plus the org
            // tokens ({{organization.name}}) resolved from the company they're on.
            RenderedMessage rendered = templated
                    ? templates.materialize(receiver, dto)
                    : templates.renderInline(receiver, dto);
            if (rendered.subject() == null) {
                throw new BadRequestException("subject and receiver (relatedParty role 'customer') are required");
            }
            CommunicationMessage entity = new CommunicationMessage();
            String id = UUID.randomUUID().toString();
            entity.setId(id);
            entity.setTenantId(tenantId);
            entity.setHref(ApiConstants.BASE_PATH + "/communicationMessage/" + id);
            entity.setSubject(rendered.subject());
            // Unsubscribe in every MARKETING message (the law + the honest
            // thing) — but a transactional send (declared by the caller)
            // must arrive verbatim, and the link is email/in-app shaped, so
            // sms/push never carry it (an OTP is byte-exact).
            String body = rendered.content() == null ? "" : rendered.content();
            String messageType = rendered.messageType() == null ? "inApp" : rendered.messageType();
            boolean marketingFooter = !dto.transactional()
                    && !"sms".equals(messageType) && !"push".equals(messageType);
            entity.setContent(marketingFooter
                    ? body + "\n\n—\nToo many emails? Unsubscribe: " + unsub.linkFor(receiver)
                    : body);
            entity.setMessageType(messageType);
            entity.setStatus(CommunicationMessage.SENT);
            entity.setReceiverPartyId(receiver);
            entity.setSource(dto.source());
            entity.setCreatedAt(OffsetDateTime.now());
            entity.setLastUpdate(OffsetDateTime.now());
            MessageView created = toView(repository.save(entity));
            events.publish("CommunicationMessageCreateEvent", "communicationMessage", created);
            // THE SANDBOX WALL: a shadow-operator clone runs the real engines
            // but may never touch the outside world — the message stays in the
            // in-app inbox, inspectable, honestly marked, never delivered.
            com.bss.communication.security.TenantRegistry.TenantEntry te = registry.byId(entity.getTenantId());
            if (te != null && te.isSandbox()) {
                entity.setDeliveryStatus("sandbox-suppressed");
                return toView(repository.save(entity));
            }
            // route to the channel's delivery seam (email/sms/push); inApp is the inbox
            channels.dispatch(entity.getTenantId(), entity.getId(), receiver,
                    entity.getSubject(), entity.getContent(), entity.getMessageType());
            if (firstCreated == null) firstCreated = created;
        }
        if (firstCreated == null && (capped > 0 || optedOut > 0)) {
            return SuppressedSend.of(capped, optedOut);
        }
        return firstCreated;
    }

    /** Reach a prospect by email — the not-yet-customer path. Email only (no
     * inbox/account); brand + event tokens still render. */
    private MessageView sendToProspect(String email, SendRequest dto) {
        String tenantId = tenantScope.currentTenantId();
        RenderedMessage rendered = templates.renderInline(null, dto);
        if (rendered.subject() == null) {
            throw new BadRequestException("subject and toEmail are required for a prospect reach");
        }
        CommunicationMessage entity = new CommunicationMessage();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setHref(ApiConstants.BASE_PATH + "/communicationMessage/" + id);
        entity.setSubject(rendered.subject());
        entity.setContent(rendered.content());
        entity.setMessageType("email");
        entity.setStatus(CommunicationMessage.SENT);
        entity.setReceiverPartyId("prospect:" + email);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        // THE SANDBOX WALL covers the prospect path too — raw-email egress is
        // exactly what a sandbox must never do
        com.bss.communication.security.TenantRegistry.TenantEntry te = registry.byId(tenantId);
        if (te != null && te.isSandbox()) {
            entity.setDeliveryStatus("sandbox-suppressed");
        }
        MessageView created = toView(repository.save(entity));
        events.publish("CommunicationMessageCreateEvent", "communicationMessage", created);
        if (te == null || !te.isSandbox()) {
            esp.forwardToEmail(tenantId, id, email, entity.getSubject(), entity.getContent());
        }
        return created;
    }

    /** The one legal change: the receiver marking their message read. */
    @Transactional
    public MessageView patch(String id, MessagePatch patch) {
        CommunicationMessage entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        requireOwn(entity);
        if (!CommunicationMessage.READ.equals(patch.status())) {
            throw new BadRequestException("the only supported change is status: 'read'");
        }
        entity.setStatus(CommunicationMessage.READ);
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(repository.save(entity));
    }

    private void requireOwn(CommunicationMessage entity) {
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getReceiverPartyId())) {
                throw NotFoundException.forResource(RESOURCE, entity.getId());
            }
        });
    }

    private MessageView toView(CommunicationMessage entity) {
        return new MessageView(entity.getId(), entity.getHref(), entity.getSubject(), entity.getContent(),
                entity.getMessageType(), entity.getStatus(), entity.getSource(), entity.getDeliveryStatus(),
                List.of(PartyRef.customer(entity.getReceiverPartyId())),
                entity.getSourceEventType() == null ? null
                        : List.of(new NameValue("sourceEventType", entity.getSourceEventType())),
                entity.getCreatedAt(), entity.getLastUpdate(), "CommunicationMessage");
    }
}
