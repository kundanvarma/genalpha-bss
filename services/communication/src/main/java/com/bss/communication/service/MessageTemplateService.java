package com.bss.communication.service;

import com.bss.communication.api.ApiConstants;
import com.bss.communication.client.PartyLookupClient;
import com.bss.communication.dto.RenderedMessage;
import com.bss.communication.dto.SendRequest;
import com.bss.communication.dto.TemplateRequest;
import com.bss.communication.dto.TemplateView;
import com.bss.communication.entity.MessageTemplate;
import com.bss.communication.exception.BadRequestException;
import com.bss.communication.exception.NotFoundException;
import com.bss.communication.repository.MessageTemplateRepository;
import com.bss.communication.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Authoring and rendering of reusable, localized, tokenized message copy — the
 * thing a journey/campaign references instead of pasting inline strings.
 */
@Service
public class MessageTemplateService {

    private static final Set<String> CHANNELS = Set.of("inApp", "email", "sms", "push", "whatsapp");
    private static final String RESOURCE = "MessageTemplate";
    private final MessageTemplateRepository repository;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;
    private final TemplateRenderer renderer;
    private final PartyLookupClient parties;

    public MessageTemplateService(MessageTemplateRepository repository, TenantScope tenantScope,
            ObjectMapper objectMapper, TemplateRenderer renderer, PartyLookupClient parties) {
        this.repository = repository;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
        this.renderer = renderer;
        this.parties = parties;
    }

    @Transactional
    public TemplateView create(TemplateRequest dto) {
        if (dto.name() == null) throw new BadRequestException("name is required");
        String channel = dto.channel() == null ? "inApp" : dto.channel();
        if (!CHANNELS.contains(channel)) {
            throw new BadRequestException("channel must be one of " + CHANNELS);
        }
        String locales = serializeLocales(dto.locales());
        MessageTemplate entity = new MessageTemplate();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/messageTemplate/" + id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setName(dto.name());
        entity.setChannel(channel);
        entity.setLocales(locales);
        if (dto.promotionRef() != null && !dto.promotionRef().isNull()) {
            entity.setPromotionRef(dto.promotionRef().asText());
        }
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<TemplateView> list() {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public TemplateView get(String id) {
        return toView(load(id));
    }

    @Transactional
    public TemplateView patch(String id, TemplateRequest patch) {
        MessageTemplate entity = load(id);
        if (patch.name() != null) entity.setName(patch.name());
        if (patch.channel() != null) {
            String channel = patch.channel();
            if (!CHANNELS.contains(channel)) throw new BadRequestException("channel must be one of " + CHANNELS);
            entity.setChannel(channel);
        }
        if (patch.locales() != null && !patch.locales().isNull()) {
            entity.setLocales(serializeLocales(patch.locales()));
        }
        // absent leaves the row alone; an explicit JSON null clears it
        if (patch.promotionRef() != null) {
            entity.setPromotionRef(patch.promotionRef().isNull() ? null : patch.promotionRef().asText());
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toView(repository.save(entity));
    }

    /** Preview: render a template with an ad-hoc context (no send). */
    @Transactional(readOnly = true)
    public Map<String, String> renderPreview(String id, String locale, Map<String, Object> context) {
        return renderer.render(load(id).getLocales(), locale, context == null ? Map.of() : context);
    }

    /**
     * Turn a send request that carries a templateRef into a concrete
     * {subject, content, messageType}: load the template, merge the caller's
     * context with the party's name tokens, render for the locale, and let the
     * template's channel drive messageType unless the caller pinned one.
     */
    @Transactional(readOnly = true)
    public RenderedMessage materialize(String partyId, SendRequest dto) {
        MessageTemplate template = load(dto.templateRef());
        Map<String, Object> context = new LinkedHashMap<>();
        if (template.getPromotionRef() != null) context.put("promotion.code", template.getPromotionRef());
        context.put("brand.name", tenantScope.currentTenantId());
        if (dto.context() != null) {
            dto.context().forEach((k, v) -> context.put(String.valueOf(k), v));
        }
        if (partyId != null) context.putAll(parties.nameTokens(tenantScope.currentTenantId(), partyId));
        Map<String, String> rendered = renderer.render(template.getLocales(), dto.locale(), context);
        // the caller's own messageType still wins over the template's channel
        return new RenderedMessage(rendered.get("subject"), rendered.get("body"),
                dto.messageType() == null ? template.getChannel() : dto.messageType());
    }

    /**
     * Personalize an INLINE message (no templateRef): if the subject/content
     * carry {{tokens}}, resolve the party's name and render them in place — so
     * "Hi {{party.firstName}}" works in any plain message box, no template
     * needed. A no-op when there are no tokens.
     */
    @Transactional(readOnly = true)
    public RenderedMessage renderInline(String partyId, SendRequest dto) {
        String subject = dto.subject() == null ? "" : dto.subject();
        String content = dto.content() == null ? "" : dto.content();
        if (!subject.contains("{{") && !content.contains("{{")) {
            return new RenderedMessage(dto.subject(), dto.content(), dto.messageType());
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("brand.name", tenantScope.currentTenantId());
        if (dto.context() != null) {
            dto.context().forEach((k, v) -> ctx.put(String.valueOf(k), v)); // order.id, tracking.url…
        }
        if (partyId != null) ctx.putAll(parties.nameTokens(tenantScope.currentTenantId(), partyId));
        return new RenderedMessage(
                dto.subject() == null ? null : renderer.substitute(subject, ctx),
                dto.content() == null ? null : renderer.substitute(content, ctx),
                dto.messageType());
    }

    private MessageTemplate load(String id) {
        return repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
    }

    private String serializeLocales(JsonNode locales) {
        if (locales == null || locales.isNull()) throw new BadRequestException("locales are required, e.g. {\"en\": {\"subject\": ..., \"body\": ...}}");
        try {
            String json = locales.isTextual() ? locales.textValue() : locales.toString();
            objectMapper.readValue(json, new TypeReference<Map<String, Map<String, String>>>() { });
            return json;
        } catch (Exception e) {
            throw new BadRequestException("locales must be a JSON map of locale -> {subject, body}");
        }
    }

    private TemplateView toView(MessageTemplate t) {
        JsonNode locales;
        try {
            locales = objectMapper.readTree(t.getLocales() == null ? "{}" : t.getLocales());
            if (!locales.isObject()) {
                locales = objectMapper.getNodeFactory().textNode(t.getLocales());
            }
        } catch (Exception e) {
            locales = objectMapper.getNodeFactory().textNode(t.getLocales());
        }
        return new TemplateView(t.getId(), t.getHref(), t.getName(), t.getChannel(), locales,
                t.getPromotionRef(), t.getLastUpdate(), "MessageTemplate");
    }
}
