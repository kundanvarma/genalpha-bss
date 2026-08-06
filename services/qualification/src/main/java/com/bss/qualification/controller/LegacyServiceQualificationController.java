package com.bss.qualification.controller;

import com.bss.qualification.entity.LegacyServiceQualification;
import com.bss.qualification.exception.BadRequestException;
import com.bss.qualification.exception.NotFoundException;
import com.bss.qualification.repository.LegacyServiceQualificationRepository;
import com.bss.qualification.security.TenantScope;
import com.bss.qualification.service.ServiceQualificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF645 v3 face for R18-era clients: serviceQualification as a TASK
 * document. Evaluation DELEGATES to the same coverage engine the v4
 * check uses — one truth, two dialects. Items the engine cannot evaluate
 * say so in a note instead of pretending a verdict.
 */
@RestController
@RequestMapping("/tmf-api/serviceQualificationManagement/v3")
public class LegacyServiceQualificationController {

    private final LegacyServiceQualificationRepository repository;
    private final ServiceQualificationService engine;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LegacyServiceQualificationController(
            LegacyServiceQualificationRepository repository,
            ServiceQualificationService engine, TenantScope tenantScope) {
        this.repository = repository;
        this.engine = engine;
        this.tenantScope = tenantScope;
    }

    @PostMapping("/serviceQualification")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> dto) {
        if (!(dto.get("serviceQualificationItem") instanceof List<?> items) || items.isEmpty()) {
            throw new BadRequestException(
                    "serviceQualificationItem is required — a qualification qualifies SOMETHING");
        }
        // one truth: run the v4 coverage engine over the same items
        List<Map<String, Object>> evaluated;
        try {
            Map<String, Object> checked = engine.check(new LinkedHashMap<>(dto));
            evaluated = checked.get("serviceQualificationItem") instanceof List<?> list
                    ? (List<Map<String, Object>>) list : List.of();
        } catch (RuntimeException e) {
            evaluated = List.of(); // engine refused the shape — items keep an honest note
        }
        List<Map<String, Object>> outItems = new ArrayList<>();
        int n = 0;
        for (Object raw : items) {
            n++;
            Map<String, Object> item = raw instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            item.putIfAbsent("id", String.valueOf(n));
            item.put("state", "done");
            Map<String, Object> verdict = n <= evaluated.size() ? evaluated.get(n - 1) : null;
            if (verdict != null && verdict.get("qualificationItemResult") != null) {
                item.put("qualificationItemResult", verdict.get("qualificationItemResult"));
                if (verdict.get("alternateServiceProposal") != null) {
                    item.put("alternateServiceProposal", verdict.get("alternateServiceProposal"));
                }
                if (verdict.get("note") != null) {
                    item.put("note", verdict.get("note"));
                }
            } else {
                item.put("note", List.of(Map.of("text", "not evaluable against this operator's"
                        + " coverage vocabulary — nothing was measured, no verdict is claimed")));
            }
            outItems.add(item);
        }
        LegacyServiceQualification task = new LegacyServiceQualification();
        String id = UUID.randomUUID().toString();
        task.setId(id);
        task.setTenantId(tenantScope.currentTenantId());
        task.setExternalId(dto.get("externalId") == null ? null
                : String.valueOf(dto.get("externalId")));
        task.setState("done");
        Map<String, Object> doc = new LinkedHashMap<>(dto);
        doc.put("serviceQualificationItem", outItems);
        try {
            task.setDocumentJson(objectMapper.writeValueAsString(doc));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new BadRequestException("unserializable qualification document");
        }
        task.setCreatedAt(OffsetDateTime.now());
        repository.save(task);
        Map<String, Object> view = view(task);
        return ResponseEntity.created(URI.create(String.valueOf(view.get("href")))).body(view);
    }

    @GetMapping("/serviceQualification")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String externalId,
            @RequestParam(name = "relatedParty.id", required = false) String relatedPartyId,
            @RequestParam(name = "relatedParty.role", required = false) String relatedPartyRole,
            @RequestParam(required = false) String fields) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LegacyServiceQualification task : repository
                .findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())) {
            Map<String, Object> view = view(task);
            if (externalId != null && !unquote(externalId).equals(view.get("externalId"))) {
                continue;
            }
            if ((relatedPartyId != null || relatedPartyRole != null)
                    && !partyMatches(view, unquote(relatedPartyId), unquote(relatedPartyRole))) {
                continue;
            }
            out.add(project(view, fields));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/serviceQualification/{id}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        LegacyServiceQualification task = repository
                .findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("ServiceQualification", id));
        return ResponseEntity.ok(project(view(task), fields));
    }

    /* ---------- internals ---------- */

    @SuppressWarnings("unchecked")
    private Map<String, Object> view(LegacyServiceQualification task) {
        Map<String, Object> map = new LinkedHashMap<>();
        try {
            if (task.getDocumentJson() != null) {
                map.putAll(objectMapper.readValue(task.getDocumentJson(), Map.class));
            }
        } catch (com.fasterxml.jackson.core.JacksonException ignored) {
            // the stored document is what we wrote; unreadable means empty
        }
        map.put("id", task.getId());
        map.put("href", "/tmf-api/serviceQualificationManagement/v3/serviceQualification/"
                + task.getId());
        map.put("state", task.getState());
        map.put("serviceQualificationDate", task.getCreatedAt().toString());
        map.putIfAbsent("serviceQualificationItem", List.of());
        map.put("@type", "ServiceQualification");
        return map;
    }

    private boolean partyMatches(Map<String, Object> view, String id, String role) {
        if (!(view.get("relatedParty") instanceof List<?> parties)) {
            return false;
        }
        for (Object p : parties) {
            if (p instanceof Map<?, ?> ref
                    && (id == null || String.valueOf(ref.get("id")).equals(id))
                    && (role == null || String.valueOf(ref.get("role")).equals(role))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> project(Map<String, Object> full, String fields) {
        if (fields == null || fields.isBlank()) {
            return full;
        }
        Map<String, Object> slim = new LinkedHashMap<>();
        if (full.containsKey("id")) {
            slim.put("id", full.get("id"));
        }
        for (String f : fields.split(",")) {
            String key = f.split("\\.")[0].trim();
            if (full.containsKey(key)) {
                slim.put(key, full.get(key));
            }
        }
        return slim;
    }

    private static String unquote(String v) {
        if (v == null || v.length() < 2) {
            return v;
        }
        char a = v.charAt(0);
        char b = v.charAt(v.length() - 1);
        return (a == b && (a == '\'' || a == '"')) ? v.substring(1, v.length() - 1) : v;
    }
}
