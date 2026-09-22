package com.bss.intelligence.aimanagement;

import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.exception.NotFoundException;
import com.bss.intelligence.security.TenantScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The registered half of the TMF915 face: one tenant-scoped document store
 * for the standard's declarative resources. A caller POSTs the TMF shape,
 * the store keeps it VERBATIM as the JSON document it is and hands it back
 * with id, href and @type — so what a kit (or an operator's tooling) writes
 * is exactly what it reads. That is the open edge: the document is a
 * {@link JsonNode}, never re-typed. Filters are exact matches on top-level
 * attributes; {@code fields=} is TMF630 attribute selection with id and
 * href always kept, so every row stays addressable.
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
    public ObjectNode create(String kind, JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            throw new BadRequestException("a " + kind + " body is required");
        }
        if (!body.isObject()) {
            throw new BadRequestException("a " + kind + " body must be a JSON object");
        }
        ObjectNode doc = body.deepCopy();
        doc.remove("id");
        doc.remove("href");
        if (!doc.hasNonNull("@type")) {
            doc.put("@type", TYPES.get(kind));
        }
        AiManagementResource row = new AiManagementResource();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenantScope.currentTenantId());
        row.setKind(kind);
        row.setCreatedAt(OffsetDateTime.now());
        apply(row, doc);
        return view(repository.save(row));
    }

    @Transactional(readOnly = true)
    public List<ObjectNode> list(String kind) {
        String tenant = tenantScope.currentTenantId();
        return repository.findByTenantIdAndKindOrderByCreatedAtAsc(tenant, kind).stream()
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public ObjectNode find(String kind, String id) {
        return view(row(kind, id));
    }

    /** A registered model, if one carries this id — the projection is asked otherwise. */
    @Transactional(readOnly = true)
    public Optional<ObjectNode> findModel(String id) {
        return repository.findByTenantIdAndKindAndId(tenantScope.currentTenantId(), "aiModel", id)
                .map(this::view);
    }

    /** JSON merge-patch on the top level; id and href stay derived. */
    @Transactional
    public ObjectNode patch(String kind, String id, JsonNode patch) {
        AiManagementResource row = row(kind, id);
        ObjectNode doc = document(row);
        if (patch != null && patch.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = patch.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if ("id".equals(e.getKey()) || "href".equals(e.getKey())) {
                    continue;
                }
                if (e.getValue() == null || e.getValue().isNull()) {
                    doc.remove(e.getKey());
                } else {
                    doc.set(e.getKey(), e.getValue());
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
    public static List<ObjectNode> filter(List<ObjectNode> rows, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return rows;
        }
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode row : rows) {
            boolean keep = true;
            for (Map.Entry<String, String> p : params.entrySet()) {
                if (RESERVED.contains(p.getKey())) {
                    continue;
                }
                JsonNode value = row.get(p.getKey());
                if (value == null || value.isNull() || !matches(value, p.getValue())) {
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

    private static boolean matches(JsonNode value, String wanted) {
        // TMF630 allows a comma-separated list of acceptable values
        for (String candidate : wanted.split(",")) {
            if (plain(value).equals(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    /** TMF630 attribute selection: the asked-for fields, plus id and href so rows stay addressable. */
    public static List<ObjectNode> select(List<ObjectNode> rows, String fields) {
        if (fields == null || fields.isBlank()) {
            return rows;
        }
        Set<String> keep = new LinkedHashSet<>(List.of("id", "href"));
        Arrays.stream(fields.split(",")).map(String::trim).filter(f -> !f.isEmpty()).forEach(keep::add);
        return rows.stream().map(row -> {
            ObjectNode slim = row.objectNode();
            for (String key : keep) {
                if (row.has(key)) {
                    slim.set(key, row.get(key));
                }
            }
            return slim;
        }).toList();
    }

    public static ObjectNode select(ObjectNode row, String fields) {
        return select(List.of(row), fields).get(0);
    }

    /* ---------- internals ---------- */

    private AiManagementResource row(String kind, String id) {
        return repository.findByTenantIdAndKindAndId(tenantScope.currentTenantId(), kind, id)
                .orElseThrow(() -> NotFoundException.forResource(TYPES.getOrDefault(kind, kind), id));
    }

    private void apply(AiManagementResource row, ObjectNode doc) {
        row.setName(doc.hasNonNull("name") ? truncate(plain(doc.get("name")), 255) : null);
        row.setState(doc.hasNonNull("state") ? truncate(plain(doc.get("state")), 64) : null);
        row.setLastUpdate(OffsetDateTime.now());
        try {
            row.setBody(objectMapper.writeValueAsString(doc));
        } catch (JsonProcessingException e) {
            throw new BadRequestException("body is not serialisable: " + e.getOriginalMessage());
        }
    }

    private ObjectNode document(AiManagementResource row) {
        try {
            JsonNode node = objectMapper.readTree(row.getBody());
            if (!(node instanceof ObjectNode object)) {
                throw new IllegalStateException("stored " + row.getKind() + " " + row.getId() + " is not a JSON object");
            }
            return object;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored " + row.getKind() + " " + row.getId() + " is not JSON", e);
        }
    }

    private ObjectNode view(AiManagementResource row) {
        ObjectNode view = objectMapper.createObjectNode();
        view.put("id", row.getId());
        view.put("href", BASE + "/" + row.getKind() + "/" + row.getId());
        ObjectNode doc = document(row);
        doc.remove("id");
        doc.remove("href");
        view.setAll(doc);
        if (!view.hasNonNull("@type")) {
            view.put("@type", TYPES.get(row.getKind()));
        }
        return view;
    }

    /** A value node as the plain text the old map-based store compared and stored. */
    private static String plain(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
