package com.bss.interaction.service;

import com.bss.interaction.api.ApiConstants;
import com.bss.interaction.api.Json;
import com.bss.interaction.api.OffsetPageRequest;
import com.bss.interaction.api.PagedResult;
import com.bss.interaction.dto.ChannelRef;
import com.bss.interaction.dto.InteractionView;
import com.bss.interaction.dto.OrgRef;
import com.bss.interaction.dto.PartyRef;
import com.bss.interaction.entity.PartyInteraction;
import com.bss.interaction.events.DomainEventPublisher;
import com.bss.interaction.exception.BadRequestException;
import com.bss.interaction.exception.NotFoundException;
import com.bss.interaction.repository.PartyInteractionRepository;
import com.bss.interaction.security.OrgScope;
import com.bss.interaction.security.PartyScope;
import com.bss.interaction.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * TMF683: the touchpoint log. Interactions are written once — by the agent
 * who handled the contact — and read by their organisation (org scope) and
 * the customer they concern (party scope).
 */
@Service
public class PartyInteractionService {

    private static final String RESOURCE = "PartyInteraction";
    /** The keys {@link InteractionView} writes itself; everything else a caller posts is an extension. */
    private static final Set<String> DECLARED = Set.of("id", "href", "description", "channel",
            "reason", "direction", "status", "sourceSystem", "relatedParty", "organization",
            "interactionDate", "lastUpdate", "@type");

    private final PartyInteractionRepository repository;
    private final DomainEventPublisher events;
    private final PartyScope partyScope;
    private final OrgScope orgScope;
    private final TenantScope tenantScope;
    private final String defaultOrg;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public PartyInteractionService(PartyInteractionRepository repository, DomainEventPublisher events,
            PartyScope partyScope, OrgScope orgScope, TenantScope tenantScope,
            @Value("${bss.org.default-org:genalpha-retail}") String defaultOrg) {
        this.repository = repository;
        this.events = events;
        this.partyScope = partyScope;
        this.orgScope = orgScope;
        this.tenantScope = tenantScope;
        this.defaultOrg = defaultOrg;
    }

    @Transactional(readOnly = true)
    public PagedResult<InteractionView> findAll(int offset, int limit, Map<String, String> filters) {
        PartyInteraction probe = new PartyInteraction();
        probe.setTenantId(tenantScope.currentTenantId());
        for (Map.Entry<String, String> f : filters.entrySet()) {
            switch (f.getKey()) {
                case "id" -> probe.setId(f.getValue());
                case "href" -> probe.setHref(f.getValue());
                case "direction" -> probe.setDirection(f.getValue());
                case "status" -> probe.setStatus(f.getValue());
                case "relatedPartyId" -> probe.setCustomerPartyId(f.getValue());
                // TMF630 permits ignoring unsupported filtering (fields/sort and
                // rich attributes like reason/channel are not indexed columns).
                default -> { }
            }
        }
        partyScope.scopedPartyId().ifPresent(probe::setCustomerPartyId);
        orgScope.scopedOrgId().ifPresent(probe::setOrgId);
        // the CSR timeline reads newest-first; the id tiebreak keeps pages stable
        Page<PartyInteraction> page = repository.findAll(Example.of(probe), new OffsetPageRequest(offset, limit,
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.desc("interactionDate"),
                        org.springframework.data.domain.Sort.Order.asc("id"))));
        return new PagedResult<>(page.getContent().stream().map(this::toView).toList(), page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public InteractionView findById(String id) {
        PartyInteraction entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(entity.getCustomerPartyId())) {
                throw NotFoundException.forResource(RESOURCE, id);
            }
        });
        orgScope.scopedOrgId().ifPresent(org -> {
            if (!org.equals(entity.getOrgId())) {
                throw NotFoundException.forResource(RESOURCE, id);
            }
        });
        return toView(entity);
    }

    @Transactional
    public InteractionView create(ObjectNode dto) {
        // TMF683: description and relatedParty are optional. Keep the customer
        // link for the CSR timeline when present; store the full body so rich
        // spec fields (channel[], reason, direction) round-trip on GET.
        String customer = customerIn(dto);
        PartyInteraction entity = new PartyInteraction();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setHref(ApiConstants.BASE_PATH + "/partyInteraction/" + id);
        entity.setPayloadJson(writeJson(dto));
        entity.setDescription(Json.present(dto.get("description")) ? Json.valueOfLike(dto.get("description")) : null);
        entity.setChannel(Json.present(dto.get("channel")) ? Json.valueOfLike(dto.get("channel")) : null);
        entity.setDirection(Json.present(dto.get("direction")) ? Json.valueOfLike(dto.get("direction")) : "inbound");
        entity.setStatus("completed");
        entity.setCustomerPartyId(customer);
        if (Json.present(dto.get("sourceSystem"))) {
            entity.setSourceSystem(Json.valueOfLike(dto.get("sourceSystem")));
        }
        entity.setAgentId(SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getName());
        entity.setOrgId(orgScope.scopedOrgId().orElse(defaultOrg));
        entity.setInteractionDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        InteractionView created = toView(repository.save(entity));
        events.publish("PartyInteractionCreateEvent", "partyInteraction", created);
        return created;
    }

    @Transactional
    public InteractionView patch(String id, ObjectNode dto) {
        PartyInteraction entity = repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("PartyInteraction", id));
        // the stored document with the patch laid over it: a key the caller
        // repeats keeps its place, a new one lands at the end
        ObjectNode merged = readJson(entity.getPayloadJson());
        merged.setAll(dto);
        entity.setPayloadJson(writeJson(merged));
        if (Json.present(dto.get("status"))) {
            entity.setStatus(Json.valueOfLike(dto.get("status")));
        }
        if (Json.present(dto.get("direction"))) {
            entity.setDirection(Json.valueOfLike(dto.get("direction")));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(repository.save(entity));
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    /** The stored document, or an empty one where there is none to read. */
    private ObjectNode readJson(String s) {
        if (s == null || s.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(s);
            return node instanceof ObjectNode object ? object : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String customerIn(ObjectNode dto) {
        JsonNode parties = dto.get("relatedParty");
        if (parties != null && parties.isArray()) {
            for (JsonNode ref : parties) {
                if (ref.isObject() && "customer".equalsIgnoreCase(Json.valueOfLike(ref.get("role")))) {
                    // A customer reference without an id has always stored the
                    // literal "null" as the link; keeping it keeps the timeline
                    // it belongs to (nobody's) exactly where it was.
                    return Json.valueOfLike(ref.get("id"));
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    /**
     * The OMNICHANNEL feed: a customer message that just went out (martech
     * blast, journey step, order notification — whatever channel spoke)
     * becomes a touchpoint on the timeline, idempotent on the source event
     * id. CSRs stop asking "what have we already said to you?" — the log
     * knows, whoever said it.
     */
    @Transactional
    public void mintTouchpoint(String sourceRef, String sourceSystem, String description,
            String channel, String customerPartyId) {
        String tenant = tenantScope.currentTenantId();
        if (repository.existsByTenantIdAndSourceRef(tenant, sourceRef)) {
            return;
        }
        PartyInteraction entity = new PartyInteraction();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setTenantId(tenant);
        entity.setHref(ApiConstants.BASE_PATH + "/partyInteraction/" + id);
        entity.setDescription(description);
        entity.setChannel(channel);
        entity.setDirection("outbound");
        entity.setStatus("completed");
        entity.setCustomerPartyId(customerPartyId);
        entity.setOrgId(defaultOrg);
        entity.setSourceRef(sourceRef);
        entity.setSourceSystem(sourceSystem);
        entity.setInteractionDate(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        try {
            repository.save(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // concurrent duplicate delivery lost the race — fine
        }
    }

    /**
     * The stored document with the server's own facts overlaid. What the
     * service derives is typed; what the caller wrote and we only keep stays
     * the node it arrived as, and anything this view does not declare rides
     * in the extensions in the order it was posted.
     */
    private InteractionView toView(PartyInteraction entity) {
        ObjectNode stored = readJson(entity.getPayloadJson());

        JsonNode description = entity.getDescription() != null
                ? TextNode.valueOf(entity.getDescription()) : stored.get("description");

        JsonNode channel = stored.get("channel");
        if (entity.getChannel() != null && !Json.present(channel)) {
            channel = TextNode.valueOf(entity.getChannel());
        }
        // TMF683 channel is an array of channel references; normalise a legacy
        // string value (app-created rows) so it round-trips as an array.
        if (channel != null && channel.isTextual()) {
            channel = objectMapper.valueToTree(List.of(new ChannelRef(channel.textValue())));
        }
        // TMF683 makes channel, direction and reason mandatory on EVERY
        // interaction. House-written rows (touchpoint feed, older writers)
        // predate that discipline — derive the trio from facts we do store,
        // so the whole history is conformant, not just API-created rows.
        if (!Json.present(channel)) {
            channel = objectMapper.valueToTree(List.of(new ChannelRef(
                    entity.getSourceSystem() != null ? entity.getSourceSystem()
                            : entity.getAgentId() != null ? "assisted" : "digital")));
        }

        JsonNode reason = stored.get("reason");
        if (!Json.present(reason)) {
            reason = TextNode.valueOf(entity.getDescription() != null
                    ? entity.getDescription() : "customer interaction");
        }

        JsonNode sourceSystem = entity.getSourceSystem() != null
                ? TextNode.valueOf(entity.getSourceSystem()) : stored.get("sourceSystem");

        // Server-derived relatedParty only when we tracked a customer (app path);
        // CTK-created interactions keep whatever relatedParty they posted.
        JsonNode relatedParty = stored.get("relatedParty");
        if (entity.getCustomerPartyId() != null) {
            List<PartyRef> parties = new ArrayList<>();
            parties.add(PartyRef.customer(entity.getCustomerPartyId()));
            if (entity.getAgentId() != null) {
                parties.add(PartyRef.agent(entity.getAgentId()));
            }
            relatedParty = objectMapper.valueToTree(parties);
        }

        return new InteractionView(
                entity.getId(),
                entity.getHref(),
                description,
                channel,
                reason,
                entity.getDirection() == null ? "inbound" : entity.getDirection(),
                entity.getStatus(),
                sourceSystem,
                relatedParty,
                entity.getOrgId() == null ? null : OrgRef.of(entity.getOrgId()),
                entity.getInteractionDate(),
                entity.getLastUpdate(),
                extensionsOf(stored));
    }

    /** The posted keys this view does not declare, in the order they were posted. */
    private Map<String, Object> extensionsOf(ObjectNode stored) {
        Map<String, Object> extras = new LinkedHashMap<>();
        stored.fields().forEachRemaining(e -> {
            if (!DECLARED.contains(e.getKey())) {
                extras.put(e.getKey(), e.getValue());
            }
        });
        return extras;
    }
}
