package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.entity.ServiceActivation;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceMonitor;
import com.bss.som.exception.BadRequestException;
import com.bss.som.exception.NotFoundException;
import com.bss.som.repository.ServiceActivationRepository;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.repository.ServiceMonitorRepository;
import com.bss.som.security.PartyScope;
import com.bss.som.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TMF640 Service Activation and Configuration — the ACTIVATION face over the
 * one service inventory. The same {@code service} row the TMF638 face reads
 * is what this face creates: POST /service declares a service as activated
 * (its state is what the caller declares, "active" by default) and records
 * it in the inventory with the caller's own document — specification ref,
 * characteristics, places, parties — kept beside it and echoed back.
 *
 * <p>What it deliberately does NOT do: it never calls the orchestrator. No
 * service order is raised, no number or SIM is drawn from a pool, no OCS or
 * entitlement provisioning runs, no domain event is published. Services the
 * orchestrator stands up from product orders are visible here too (that is
 * the point of one inventory), with their activation date being the moment
 * they came up. A monitor row is born "Completed" with every POST, because
 * activation through this face is synchronous.
 */
@RestController
public class ServiceActivationController {

    private static final String BASE = ApiConstants.ACTIVATION_BASE;
    /** The marker in {@code service.service_order_id} for a line declared through this face. */
    static final String DECLARED_BY = "tmf640";
    private static final DateTimeFormatter SERVICE_DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Set<String> NOT_FILTERS = Set.of("fields", "offset", "limit");
    private static final int JSON_COLUMN = 16000;

    private final SomController som;
    private final ServiceInstanceRepository services;
    private final ServiceActivationRepository activations;
    private final ServiceMonitorRepository monitors;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ServiceActivationController(SomController som, ServiceInstanceRepository services,
            ServiceActivationRepository activations, ServiceMonitorRepository monitors,
            TenantScope tenantScope, PartyScope partyScope) {
        this.som = som;
        this.services = services;
        this.activations = activations;
        this.monitors = monitors;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    /* ---------- service ---------- */

    @PostMapping({BASE + "/service", BASE + "/service/"})
    public ResponseEntity<Map<String, Object>> activate(@RequestBody Map<String, Object> dto) {
        Map<?, ?> spec = dto.get("serviceSpecification") instanceof Map<?, ?> m
                && m.get("id") != null && !String.valueOf(m.get("id")).isBlank() ? m : null;
        String name = text(dto.get("name"));
        if (name == null && spec != null) {
            name = text(spec.get("name")) != null ? text(spec.get("name")) : String.valueOf(spec.get("id"));
        }
        if (name == null) {
            throw new BadRequestException(
                    "serviceSpecification {id} or name is required — an activation says WHAT is activated");
        }
        String state = text(dto.get("state")) != null ? text(dto.get("state")) : ServiceInstance.ACTIVE;
        String tenant = tenantScope.currentTenantId();
        String owner = partyScope.scopedPartyId().orElseGet(() -> customerOf(dto));
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime serviceDate = parseDate(dto.get("serviceDate")).orElse(now);

        ServiceInstance s = new ServiceInstance();
        String id = UUID.randomUUID().toString();
        s.setId(id);
        s.setTenantId(tenant);
        s.setHref(ApiConstants.INVENTORY_BASE + "/service/" + id);
        s.setName(name);
        s.setState(state);
        s.setServiceOrderId(DECLARED_BY);
        s.setOwnerPartyId(owner);
        s.setCreatedAt(now);
        s.setLastUpdate(now);
        services.save(s);

        Map<String, Object> document = new LinkedHashMap<>(dto);
        document.remove("id");
        document.remove("href");
        ServiceActivation a = new ServiceActivation();
        a.setServiceId(id);
        a.setTenantId(tenant);
        a.setServiceDate(serviceDate);
        a.setDocumentJson(fit(writeJson(document)));
        a.setCreatedAt(now);
        activations.save(a);

        Map<String, Object> view = view(s, a);
        ServiceMonitor monitor = new ServiceMonitor();
        monitor.setId(UUID.randomUUID().toString());
        monitor.setTenantId(tenant);
        monitor.setServiceId(id);
        monitor.setState(ServiceMonitor.COMPLETED);
        monitor.setSourceHref(String.valueOf(view.get("href")));
        monitor.setRequestJson(fit(writeJson(Map.of("method", "POST", "to", BASE + "/service", "body", document))));
        monitor.setResponseJson(fit(writeJson(Map.of("statusCode", 201, "body", view))));
        monitor.setCreatedAt(now);
        monitors.save(monitor);

        return ResponseEntity.created(URI.create(String.valueOf(view.get("href"))))
                .header("monitorId", monitor.getId())
                .body(view);
    }

    @GetMapping({BASE + "/service", BASE + "/service/"})
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam Map<String, String> params) {
        String tenant = tenantScope.currentTenantId();
        Optional<String> own = partyScope.scopedPartyId();
        Map<String, ServiceActivation> declared = activations.findByTenantId(tenant).stream()
                .collect(Collectors.toMap(ServiceActivation::getServiceId, Function.identity(), (x, y) -> x));
        int offset = intParam(params, "offset", 0);
        int limit = intParam(params, "limit", 100);
        List<Map<String, Object>> out = services.findByTenantIdOrderByCreatedAtDesc(tenant).stream()
                .filter(s -> own.map(o -> o.equals(s.getOwnerPartyId())).orElse(true))
                .map(s -> view(s, declared.get(s.getId())))
                .filter(v -> matches(v, params))
                .skip(offset).limit(limit)
                .map(v -> project(v, params.get("fields")))
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping(BASE + "/service/{id}")
    public ResponseEntity<Map<String, Object>> byId(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        String tenant = tenantScope.currentTenantId();
        ServiceInstance s = services.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> NotFoundException.forResource("Service", id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(s.getOwnerPartyId())) {
                throw NotFoundException.forResource("Service", id);
            }
        });
        ServiceActivation a = activations.findByServiceIdAndTenantId(id, tenant).orElse(null);
        return ResponseEntity.ok(project(view(s, a), fields));
    }

    /* ---------- monitor ---------- */

    @GetMapping(BASE + "/monitor")
    public ResponseEntity<List<Map<String, Object>>> monitors(@RequestParam Map<String, String> params) {
        List<Map<String, Object>> out = monitors
                .findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .map(this::monitorView)
                .filter(v -> matches(v, params))
                .map(v -> project(v, params.get("fields")))
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping(BASE + "/monitor/{id}")
    public ResponseEntity<Map<String, Object>> monitor(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        ServiceMonitor m = monitors.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Monitor", id));
        return ResponseEntity.ok(project(monitorView(m), fields));
    }

    /* ---------- views ---------- */

    /**
     * The inventory view, overlaid with what the caller declared (its
     * document wins on every key it carries), then the facts this face owns:
     * id, its own href, the live name and state, the activation date.
     */
    private Map<String, Object> view(ServiceInstance s, ServiceActivation a) {
        Map<String, Object> map = new LinkedHashMap<>(som.serviceMap(s));
        Map<String, Object> doc = a == null ? null : readJson(a.getDocumentJson());
        if (doc != null) {
            doc.remove("id");
            doc.remove("href");
            map.putAll(doc);
        }
        if (DECLARED_BY.equals(s.getServiceOrderId())) {
            // no order of ours stood this service up — the caller declared it.
            // Its provisioning record is the activation monitor, not a phantom order.
            map.remove("serviceOrderId");
            if (doc == null || !doc.containsKey("supportingResource")) {
                map.put("supportingResource", monitors
                        .findFirstByTenantIdAndServiceIdOrderByCreatedAtDesc(s.getTenantId(), s.getId())
                        .map(m -> List.<Map<String, Object>>of(Map.of(
                                "id", m.getId(), "href", BASE + "/monitor/" + m.getId(),
                                "@referredType", "Monitor",
                                "note", "declared through the activation face — nothing of ours was drawn")))
                        .orElse(List.of()));
            }
        }
        map.put("id", s.getId());
        map.put("href", BASE + "/service/" + s.getId());
        map.put("name", s.getName());
        map.put("state", s.getState());
        map.put("serviceDate", SERVICE_DATE.format(a != null ? a.getServiceDate() : s.getCreatedAt()));
        map.putIfAbsent("@type", "Service");
        return map;
    }

    private Map<String, Object> monitorView(ServiceMonitor m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("href", BASE + "/monitor/" + m.getId());
        map.put("state", m.getState());
        map.put("sourceHref", m.getSourceHref());
        map.put("service", Map.of("id", m.getServiceId(), "href", BASE + "/service/" + m.getServiceId()));
        Map<String, Object> request = readJson(m.getRequestJson());
        Map<String, Object> response = readJson(m.getResponseJson());
        map.put("request", request != null ? request : Map.of("method", "POST", "to", BASE + "/service"));
        map.put("response", response != null ? response : Map.of("statusCode", 201));
        map.put("createdAt", m.getCreatedAt().toString());
        map.put("@type", "Monitor");
        return map;
    }

    /** TMF630 attribute selection on this face: the asked-for fields, id always along. */
    private Map<String, Object> project(Map<String, Object> full, String fields) {
        if (fields == null || fields.isBlank()) {
            return full;
        }
        Map<String, Object> slim = new LinkedHashMap<>();
        slim.put("id", full.get("id"));
        for (String f : fields.split(",")) {
            String key = f.trim();
            if (full.containsKey(key)) {
                slim.put(key, full.get(key));
            }
        }
        return slim;
    }

    /** Every query parameter that is not a paging knob is an equality filter, dotted paths allowed. */
    private boolean matches(Map<String, Object> view, Map<String, String> params) {
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (NOT_FILTERS.contains(e.getKey())) {
                continue;
            }
            if (!valueMatches(view, e.getKey().split("\\."), 0, e.getValue())) {
                return false;
            }
        }
        return true;
    }

    private boolean valueMatches(Object node, String[] path, int i, String expected) {
        if (node instanceof List<?> list) {
            return list.stream().anyMatch(n -> valueMatches(n, path, i, expected));
        }
        if (i == path.length) {
            return node != null && expected.equals(String.valueOf(node));
        }
        if (node instanceof Map<?, ?> m) {
            return valueMatches(m.get(path[i]), path, i + 1, expected);
        }
        return false;
    }

    /* ---------- helpers ---------- */

    private static String customerOf(Map<String, Object> dto) {
        if (!(dto.get("relatedParty") instanceof List<?> parties)) {
            return null;
        }
        for (Object p : parties) {
            if (p instanceof Map<?, ?> party && "customer".equals(party.get("role"))
                    && party.get("id") != null) {
                return String.valueOf(party.get("id"));
            }
        }
        return null;
    }

    private static Optional<OffsetDateTime> parseDate(Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(s).truncatedTo(ChronoUnit.MILLIS));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String text(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    private static int intParam(Map<String, String> params, String key, int fallback) {
        try {
            return params.containsKey(key) ? Math.max(0, Integer.parseInt(params.get(key))) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A document too large for its column is dropped, never silently cut mid-JSON. */
    private static String fit(String json) {
        return json == null || json.length() > JSON_COLUMN ? null : json;
    }

    private String writeJson(Object o) {
        try {
            return o == null ? null : objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }
}
