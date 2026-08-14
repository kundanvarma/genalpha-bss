package com.bss.insight.service;

import com.bss.insight.api.ApiConstants;
import com.bss.insight.client.AnalyticsForwarder;
import com.bss.insight.entity.Audience;
import com.bss.insight.entity.VisitorProfile;
import com.bss.insight.repository.AudienceRepository;
import com.bss.insight.repository.VisitorEventRepository;
import com.bss.insight.repository.VisitorProfileRepository;
import com.bss.insight.security.TenantScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Saved audiences with a criteria tree, evaluated against the consented,
 * stitched profiles this service holds. The tree is the marketer-friendly
 * replacement for a bare segment string.
 */
@Service
public class AudienceService {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private final AudienceRepository audiences;
    private final VisitorProfileRepository profiles;
    private final VisitorEventRepository events;
    private final AnalyticsForwarder analytics;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public AudienceService(AudienceRepository audiences, VisitorProfileRepository profiles,
            VisitorEventRepository events, AnalyticsForwarder analytics, TenantScope tenantScope,
            ObjectMapper objectMapper) {
        this.audiences = audiences;
        this.profiles = profiles;
        this.events = events;
        this.analytics = analytics;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        if (dto.get("name") == null || dto.get("criteria") == null) {
            throw new IllegalArgumentException("name and criteria are required");
        }
        Audience a = new Audience();
        String id = UUID.randomUUID().toString();
        a.setId(id);
        a.setHref(ApiConstants.BASE_PATH + "/audience/" + id);
        a.setTenantId(tenantScope.currentTenantId());
        a.setName(String.valueOf(dto.get("name")));
        a.setCriteria(serialize(dto.get("criteria")));
        a.setCreatedAt(OffsetDateTime.now());
        a.setLastUpdate(OffsetDateTime.now());
        return toMap(audiences.save(a));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return audiences.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return toMap(load(id));
    }

    @Transactional
    public Map<String, Object> patch(String id, Map<String, Object> patch) {
        Audience a = load(id);
        if (patch.get("name") != null) a.setName(String.valueOf(patch.get("name")));
        if (patch.get("criteria") != null) a.setCriteria(serialize(patch.get("criteria")));
        a.setLastUpdate(OffsetDateTime.now());
        return toMap(audiences.save(a));
    }

    /**
     * Evaluate the criteria tree against every consented, stitched profile and
     * return the matching parties — the same consent spine as segmentMembers:
     * a campaign can never reach someone consent does not cover.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(String id) {
        Object criteria = parse(load(id).getCriteria());
        String tenantId = tenantScope.currentTenantId();
        Set<String> parties = new LinkedHashSet<>();
        for (VisitorProfile p : profiles.findByTenantIdAndPartyIdIsNotNull(tenantId)) {
            if (!p.isPersonalizationConsent() || parties.contains(p.getPartyId())) {
                continue;
            }
            Set<String> interests = new LinkedHashSet<>();
            for (Object[] row : events.interestsOf(tenantId, p.getVisitorId())) {
                if (row.length > 0 && row[0] != null) interests.add(String.valueOf(row[0]));
            }
            List<String> ga4 = analytics.audiencesOf(tenantId, p.getVisitorId());
            if (matches(criteria, interests, ga4)) {
                parties.add(p.getPartyId());
            }
        }
        return parties.stream().map(pid -> Map.<String, Object>of("partyId", pid)).toList();
    }

    /** Recursive evaluation: {all|any:[..]} | {not:{..}} | {type,value} leaf. */
    @SuppressWarnings("unchecked")
    private boolean matches(Object node, Set<String> interests, List<String> ga4) {
        if (!(node instanceof Map<?, ?> m)) {
            return false;
        }
        if (m.get("all") instanceof List<?> all) {
            return all.stream().allMatch(c -> matches(c, interests, ga4));
        }
        if (m.get("any") instanceof List<?> any) {
            return any.stream().anyMatch(c -> matches(c, interests, ga4));
        }
        if (m.get("not") != null) {
            return !matches(m.get("not"), interests, ga4);
        }
        String type = String.valueOf(m.get("type"));
        String value = String.valueOf(m.get("value"));
        return switch (type) {
            case "interest" -> interests.contains(value);       // first-party behaviour
            case "audience" -> ga4.contains(value);             // analytics-computed audience
            default -> false;                                    // unknown leaf never matches
        };
    }

    private Audience load(String id) {
        return audiences.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> new IllegalArgumentException("audience not found: " + id));
    }

    private String serialize(Object criteria) {
        try {
            String json = criteria instanceof String s ? s : objectMapper.writeValueAsString(criteria);
            objectMapper.readValue(json, OBJECT); // validate it parses as an object
            return json;
        } catch (Exception e) {
            throw new IllegalArgumentException("criteria must be a JSON object tree");
        }
    }

    private Object parse(String criteria) {
        try {
            return objectMapper.readValue(criteria == null ? "{}" : criteria, OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> toMap(Audience a) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", a.getId());
        map.put("href", a.getHref());
        map.put("name", a.getName());
        try {
            map.put("criteria", objectMapper.readValue(a.getCriteria() == null ? "{}" : a.getCriteria(), OBJECT));
        } catch (Exception e) {
            map.put("criteria", a.getCriteria());
        }
        map.put("lastUpdate", a.getLastUpdate());
        map.put("@type", "Audience");
        return map;
    }
}
