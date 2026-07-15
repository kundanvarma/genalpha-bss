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

    public CommunicationMessageService(CommunicationMessageRepository repository, DomainEventPublisher events,
            PartyScope partyScope, TenantScope tenantScope, com.bss.communication.client.EspForwarder esp) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.esp = esp;
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
        esp.forward(tenantId, n.partyId(), n.subject(), n.content());
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
        String receiver = receiverIn(dto);
        if (receiver == null || dto.get("subject") == null) {
            throw new BadRequestException("subject and receiver (relatedParty role 'customer') are required");
        }
        CommunicationMessage entity = new CommunicationMessage();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/communicationMessage/" + id);
        entity.setSubject(String.valueOf(dto.get("subject")));
        entity.setContent(dto.get("content") == null ? null : String.valueOf(dto.get("content")));
        entity.setMessageType(dto.get("messageType") == null ? "inApp" : String.valueOf(dto.get("messageType")));
        entity.setStatus(CommunicationMessage.SENT);
        entity.setReceiverPartyId(receiver);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        Map<String, Object> created = toMap(repository.save(entity));
        events.publish("CommunicationMessageCreateEvent", "communicationMessage", created);
        esp.forward(entity.getTenantId(), receiver, entity.getSubject(), entity.getContent());
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
