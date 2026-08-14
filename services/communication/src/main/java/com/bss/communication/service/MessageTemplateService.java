package com.bss.communication.service;

import com.bss.communication.api.ApiConstants;
import com.bss.communication.client.PartyLookupClient;
import com.bss.communication.entity.MessageTemplate;
import com.bss.communication.exception.BadRequestException;
import com.bss.communication.exception.NotFoundException;
import com.bss.communication.repository.MessageTemplateRepository;
import com.bss.communication.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
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

    private static final Set<String> CHANNELS = Set.of("inApp", "email", "sms", "push");
    private static final String RESOURCE = "MessageTemplate";
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

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
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null) throw new BadRequestException("name is required");
        String channel = dto.get("channel") == null ? "inApp" : String.valueOf(dto.get("channel"));
        if (!CHANNELS.contains(channel)) {
            throw new BadRequestException("channel must be one of " + CHANNELS);
        }
        String locales = serializeLocales(dto.get("locales"));
        MessageTemplate entity = new MessageTemplate();
        String id = UUID.randomUUID().toString();
        entity.setId(id);
        entity.setHref(ApiConstants.BASE_PATH + "/messageTemplate/" + id);
        entity.setTenantId(tenantScope.currentTenantId());
        entity.setName(String.valueOf(dto.get("name")));
        entity.setChannel(channel);
        entity.setLocales(locales);
        if (dto.get("promotionRef") != null) entity.setPromotionRef(String.valueOf(dto.get("promotionRef")));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return toMap(load(id));
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        MessageTemplate entity = load(id);
        if (patch.get("name") != null) entity.setName(String.valueOf(patch.get("name")));
        if (patch.get("channel") != null) {
            String channel = String.valueOf(patch.get("channel"));
            if (!CHANNELS.contains(channel)) throw new BadRequestException("channel must be one of " + CHANNELS);
            entity.setChannel(channel);
        }
        if (patch.get("locales") != null) entity.setLocales(serializeLocales(patch.get("locales")));
        if (patch.containsKey("promotionRef")) {
            entity.setPromotionRef(patch.get("promotionRef") == null ? null
                    : String.valueOf(patch.get("promotionRef")));
        }
        entity.setLastUpdate(OffsetDateTime.now());
        return toMap(repository.save(entity));
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
    @SuppressWarnings("unchecked")
    public Map<String, Object> materialize(String partyId, Map<String, Object> dto) {
        MessageTemplate template = load(String.valueOf(dto.get("templateRef")));
        Map<String, Object> context = new LinkedHashMap<>();
        if (template.getPromotionRef() != null) context.put("promotion.code", template.getPromotionRef());
        context.put("brand.name", tenantScope.currentTenantId());
        if (dto.get("context") instanceof Map<?, ?> ctx) {
            ctx.forEach((k, v) -> context.put(String.valueOf(k), v));
        }
        if (partyId != null) context.putAll(parties.nameTokens(tenantScope.currentTenantId(), partyId));
        String locale = dto.get("locale") == null ? null : String.valueOf(dto.get("locale"));
        Map<String, String> rendered = renderer.render(template.getLocales(), locale, context);
        Map<String, Object> out = new LinkedHashMap<>(dto);
        out.put("subject", rendered.get("subject"));
        out.put("content", rendered.get("body"));
        out.putIfAbsent("messageType", template.getChannel());
        return out;
    }

    /**
     * Personalize an INLINE message (no templateRef): if the subject/content
     * carry {{tokens}}, resolve the party's name and render them in place — so
     * "Hi {{party.firstName}}" works in any plain message box, no template
     * needed. A no-op when there are no tokens.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> renderInline(String partyId, Map<String, Object> dto) {
        String subject = dto.get("subject") == null ? "" : String.valueOf(dto.get("subject"));
        String content = dto.get("content") == null ? "" : String.valueOf(dto.get("content"));
        if (!subject.contains("{{") && !content.contains("{{")) {
            return dto;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("brand.name", tenantScope.currentTenantId());
        if (dto.get("context") instanceof Map<?, ?> passed) {
            passed.forEach((k, v) -> ctx.put(String.valueOf(k), v)); // event tokens: order.id, tracking.url…
        }
        if (partyId != null) ctx.putAll(parties.nameTokens(tenantScope.currentTenantId(), partyId));
        Map<String, Object> out = new LinkedHashMap<>(dto);
        if (dto.get("subject") != null) out.put("subject", renderer.substitute(subject, ctx));
        if (dto.get("content") != null) out.put("content", renderer.substitute(content, ctx));
        return out;
    }

    private MessageTemplate load(String id) {
        return repository.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource(RESOURCE, id));
    }

    private String serializeLocales(Object locales) {
        if (locales == null) throw new BadRequestException("locales are required, e.g. {\"en\": {\"subject\": ..., \"body\": ...}}");
        try {
            String json = locales instanceof String s ? s : objectMapper.writeValueAsString(locales);
            objectMapper.readValue(json, new TypeReference<Map<String, Map<String, String>>>() { });
            return json;
        } catch (Exception e) {
            throw new BadRequestException("locales must be a JSON map of locale -> {subject, body}");
        }
    }

    private Map<String, Object> toMap(MessageTemplate t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", t.getId());
        map.put("href", t.getHref());
        map.put("name", t.getName());
        map.put("channel", t.getChannel());
        try {
            map.put("locales", objectMapper.readValue(t.getLocales() == null ? "{}" : t.getLocales(), OBJECT));
        } catch (Exception e) {
            map.put("locales", t.getLocales());
        }
        if (t.getPromotionRef() != null) map.put("promotionRef", t.getPromotionRef());
        map.put("lastUpdate", t.getLastUpdate());
        map.put("@type", "MessageTemplate");
        return map;
    }
}
