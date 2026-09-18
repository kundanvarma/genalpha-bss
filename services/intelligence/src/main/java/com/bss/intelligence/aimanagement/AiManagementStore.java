package com.bss.intelligence.aimanagement;

import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.exception.NotFoundException;
import com.bss.intelligence.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The registered half of the TMF915 face: one tenant-scoped document store
 * for the standard's declarative resources. A caller POSTs the TMF shape,
 * the store keeps it verbatim and hands it back with id, href and @type —
 * so what a kit (or an operator's tooling) writes is exactly what it reads.
 * Filters are exact matches on top-level attributes; {@code fields=} is
 * TMF630 attribute selection with id and href always kept, so every row
 * stays addressable.
 */
@Service
public class AiManagementStore {

    public static final String BASE = "/tmf-api/aiManagement/v4";

    /** resource path segment -> default @type */
    private static final Map<String, String> TYPES = Map.of(
            "alarm", "Alarm",
            "rule", "Rule",
            "aiContract", "AiContract",
            "aiContractSpecification", "AiContractSpecification",
            "aiContractViolation", "AiContractViolation",
            "aiModelSpecification", "AiModelSpecification",
            "aiModel", "AiModel");

    /** query parameters that are never attribute filters */
    private static final Set<String> RESERVED = Set.of("fields", "offset", "limit", "sort");

    private static final TypeReference<LinkedHashMap<String, Object>> DOC =
            new TypeReference<>() { };

    private final AiManagementResourceRepository repository;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public AiManagementStore(AiManagementResourceRepository repository, TenantScope tenantScope,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> create(String kind, Map<String, Object> body) {
        if (body == null) {
            throw new BadRequestException("a " + kind + " body is required");
        }
        Map<String, Object> doc = new LinkedHashMap<>(body);
        doc.remove("id");
        doc.remove("href");
        doc.putIfAbsent("@type", TYPES.get(kind));
        AiManagementResource row = new AiManagementResource();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setKind(kind);
        row.setCreatedAt(OffsetDateTime.now());
        apply(row, doc);
        return view(repository.save(row));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String kind) {
        String tenant = tenantScope.currentTenantId();
        return repository.findByTenantIdAndKindOrderByCreatedAtAsc(tenant, kind).stream()
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> find(String kind, String id) {
        return view(row(kind, id));
    }

    /** A registered model, if one carries this id — the projection is asked otherwise. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> findModel(String id) {
        return repository.findByTenantIdAndKindAndId(tenantScope.currentTenantId(), "aiModel", id)
                .map(this::view);
    }

    /** JSON merge-patch on the top level; id and href stay derived. */
    @Transactional
    public Map<String, Object> patch(String kind, String id, Map<String, Object> patch) {
        AiManagementResource row = row(kind, id);
        Map<String, Object> doc = document(row);
        if (patch != null) {
            for (Map.Entry<String, Object> e : patch.entrySet()) {
                if ("id".equals(e.getKey()) || "href".equals(e.getKey())) {
                    continue;
                }
                if (e.getValue() == null) {
                    doc.remove(e.getKey());
                } else {
                    doc.put(e.getKey(), e.getValue());
                }
            }
        }
        apply(row, doc);
        return view(repository.save(row));
    }

    @Transactional
    public void delete(String kind, String id) {
        repository.delete(row(kind, id));
    }

    /* ---------- the generic list mechanics, shared with the projection ---------- */

    /** Exact-match attribute filters: every non-reserved query parameter must equal the row's value. */
    public static List<Map<String, Object>> filter(List<Map<String, Object>> rows, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean keep = true;
            for (Map.Entry<String, String> p : params.entrySet()) {
                if (RESERVED.contains(p.getKey())) {
                    continue;
                }
                Object value = row.get(p.getKey());
                if (value == null || !matches(value, p.getValue())) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                out.add(row);
            }
        }
        return out;
    }

    private static boolean matches(Object value, String wanted) {
        // TMF630 allows a comma-separated list of acceptable values
        for (String candidate : wanted.split(",")) {
            if (String.valueOf(value).equals(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    /** TMF630 attribute selection: the asked-for fields, plus id and href so rows stay addressable. */
    public static List<Map<String, Object>> select(List<Map<String, Object>> rows, String fields) {
        if (fields == null || fields.isBlank()) {
            return rows;
        }
        Set<String> keep = new LinkedHashSet<>(List.of("id", "href"));
        Arrays.stream(fields.split(",")).map(String::trim).filter(f -> !f.isEmpty()).forEach(keep::add);
        return rows.stream().map(row -> {
            Map<String, Object> slim = new LinkedHashMap<>();
            for (String key : keep) {
                if (row.containsKey(key)) {
                    slim.put(key, row.get(key));
                }
            }
            return slim;
        }).toList();
    }

    public static Map<String, Object> select(Map<String, Object> row, String fields) {
        return select(List.of(row), fields).get(0);
    }

    /* ---------- internals ---------- */

    private AiManagementResource row(String kind, String id) {
        return repository.findByTenantIdAndKindAndId(tenantScope.currentTenantId(), kind, id)
                .orElseThrow(() -> NotFoundException.forResource(TYPES.getOrDefault(kind, kind), id));
    }

    private void apply(AiManagementResource row, Map<String, Object> doc) {
        row.setName(doc.get("name") == null ? null : truncate(String.valueOf(doc.get("name")), 255));
        row.setState(doc.get("state") == null ? null : truncate(String.valueOf(doc.get("state")), 64));
        row.setLastUpdate(OffsetDateTime.now());
        try {
            row.setBody(objectMapper.writeValueAsString(doc));
        } catch (JsonProcessingException e) {
            throw new BadRequestException("body is not serialisable: " + e.getOriginalMessage());
        }
    }

    private Map<String, Object> document(AiManagementResource row) {
        try {
            return objectMapper.readValue(row.getBody(), DOC);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored " + row.getKind() + " " + row.getId() + " is not JSON", e);
        }
    }

    private Map<String, Object> view(AiManagementResource row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("href", BASE + "/" + row.getKind() + "/" + row.getId());
        Map<String, Object> doc = document(row);
        doc.remove("id");
        doc.remove("href");
        view.putAll(doc);
        view.putIfAbsent("@type", TYPES.get(row.getKind()));
        return view;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
