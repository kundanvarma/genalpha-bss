package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.api.Projection;
import com.bss.som.dto.ServiceRef;
import com.bss.som.dto.ServiceView;
import com.bss.som.dto.StandardFaceViews.MonitorRequest;
import com.bss.som.dto.StandardFaceViews.MonitorResponse;
import com.bss.som.dto.StandardFaceViews.MonitorView;
import com.bss.som.entity.ServiceActivation;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceMonitor;
import com.bss.som.exception.BadRequestException;
import com.bss.som.exception.NotFoundException;
import com.bss.som.mapper.ServiceViews;
import com.bss.som.repository.ServiceActivationRepository;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.repository.ServiceMonitorRepository;
import com.bss.som.security.PartyScope;
import com.bss.som.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * characteristics, places, parties — kept beside it VERBATIM and echoed
 * back over the inventory view's tree.
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

    private final ServiceViews serviceViews;
    private final ServiceInstanceRepository services;
    private final ServiceActivationRepository activations;
    private final ServiceMonitorRepository monitors;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final ObjectMapper objectMapper;

    public ServiceActivationController(ServiceViews serviceViews, ServiceInstanceRepository services,
            ServiceActivationRepository activations, ServiceMonitorRepository monitors,
            TenantScope tenantScope, PartyScope partyScope, ObjectMapper objectMapper) {
        this.serviceViews = serviceViews;
        this.services = services;
        this.activations = activations;
        this.monitors = monitors;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.objectMapper = objectMapper;
    }

    /* ---------- service ---------- */

    @PostMapping({BASE + "/service", BASE + "/service/"})
    public ResponseEntity<JsonNode> activate(@RequestBody ObjectNode dto) {
        JsonNode spec = dto.path("serviceSpecification");
        boolean specified = spec.isObject() && !spec.path("id").asText("").isBlank();
        String name = text(dto.get("name"));
        if (name == null && specified) {
            name = text(spec.get("name")) != null ? text(spec.get("name")) : spec.get("id").asText();
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

        ObjectNode document = dto.deepCopy();
        document.remove("id");
        document.remove("href");
        ServiceActivation a = new ServiceActivation();
        a.setServiceId(id);
        a.setTenantId(tenant);
        a.setServiceDate(serviceDate);
        a.setDocumentJson(fit(writeJson(document)));
        a.setCreatedAt(now);
        activations.save(a);

        ObjectNode view = view(s, a);
        ServiceMonitor monitor = new ServiceMonitor();
        monitor.setId(UUID.randomUUID().toString());
        monitor.setTenantId(tenant);
        monitor.setServiceId(id);
        monitor.setState(ServiceMonitor.COMPLETED);
        monitor.setSourceHref(view.get("href").asText());
        monitor.setRequestJson(fit(writeJson(new MonitorRequest("POST", BASE + "/service", document))));
        monitor.setResponseJson(fit(writeJson(new MonitorResponse(201, view))));
        monitor.setCreatedAt(now);
        monitors.save(monitor);

        return ResponseEntity.created(URI.create(view.get("href").asText()))
                .header("monitorId", monitor.getId())
                .body(view);
    }

    @GetMapping({BASE + "/service", BASE + "/service/"})
    public ResponseEntity<List<JsonNode>> list(@RequestParam Map<String, String> params) {
        String tenant = tenantScope.currentTenantId();
        Optional<String> own = partyScope.scopedPartyId();
        Map<String, ServiceActivation> declared = activations.findByTenantId(tenant).stream()
                .collect(Collectors.toMap(ServiceActivation::getServiceId, Function.identity(), (x, y) -> x));
        int offset = intParam(params, "offset", 0);
        int limit = intParam(params, "limit", 100);
        List<JsonNode> out = services.findByTenantIdOrderByCreatedAtDesc(tenant).stream()
                .filter(s -> own.map(o -> o.equals(s.getOwnerPartyId())).orElse(true))
                .map(s -> view(s, declared.get(s.getId())))
                .filter(v -> matches(v, params))
                .skip(offset).limit(limit)
                .map(v -> Projection.selectExact(objectMapper, v, params.get("fields"), "id"))
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping(BASE + "/service/{id}")
    public ResponseEntity<JsonNode> byId(@PathVariable("id") String id,
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
        return ResponseEntity.ok(Projection.selectExact(objectMapper, view(s, a), fields, "id"));
    }

    /* ---------- monitor ---------- */

    @GetMapping(BASE + "/monitor")
    public ResponseEntity<List<JsonNode>> monitors(@RequestParam Map<String, String> params) {
        List<JsonNode> out = monitors
                .findTop100ByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId()).stream()
                .map(m -> (JsonNode) objectMapper.valueToTree(monitorView(m)))
                .filter(v -> matches(v, params))
                .map(v -> Projection.selectExact(objectMapper, v, params.get("fields"), "id"))
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping(BASE + "/monitor/{id}")
    public ResponseEntity<JsonNode> monitor(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        ServiceMonitor m = monitors.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Monitor", id));
        return ResponseEntity.ok(Projection.selectExact(objectMapper, monitorView(m), fields, "id"));
    }

    /* ---------- views ---------- */

    /**
     * The inventory view, overlaid with what the caller declared (its
     * document wins on every key it carries), then the facts this face owns:
     * id, its own href, the live name and state, the activation date.
     */
    private ObjectNode view(ServiceInstance s, ServiceActivation a) {
        ObjectNode map = objectMapper.valueToTree(serviceViews.view(s));
        ObjectNode doc = a == null ? null : readJson(a.getDocumentJson());
        if (doc != null) {
            doc.remove("id");
            doc.remove("href");
            map.setAll(doc);
        }
        if (DECLARED_BY.equals(s.getServiceOrderId())) {
            // no order of ours stood this service up — the caller declared it.
            // Its provisioning record is the activation monitor, not a phantom order.
            map.remove("serviceOrderId");
            if (doc == null || !doc.has("supportingResource")) {
                List<ServiceView.ResourceRef> record = monitors
                        .findFirstByTenantIdAndServiceIdOrderByCreatedAtDesc(s.getTenantId(), s.getId())
                        .map(m -> List.of(ServiceView.ResourceRef.monitor(m.getId(), BASE + "/monitor/" + m.getId())))
                        .orElse(List.of());
                map.set("supportingResource", objectMapper.valueToTree(record));
            }
        }
        map.put("id", s.getId());
        map.put("href", BASE + "/service/" + s.getId());
        map.put("name", s.getName());
        map.put("state", s.getState());
        map.put("serviceDate", SERVICE_DATE.format(a != null ? a.getServiceDate() : s.getCreatedAt()));
        if (!map.has("@type")) {
            map.put("@type", "Service");
        }
        return map;
    }

    private MonitorView monitorView(ServiceMonitor m) {
        JsonNode request = readJson(m.getRequestJson());
        JsonNode response = readJson(m.getResponseJson());
        return new MonitorView(m.getId(), BASE + "/monitor/" + m.getId(), m.getState(), m.getSourceHref(),
                ServiceRef.at(m.getServiceId(), BASE + "/service/" + m.getServiceId()),
                request != null ? request : objectMapper.valueToTree(new MonitorRequest("POST", BASE + "/service", null)),
                response != null ? response : objectMapper.valueToTree(new MonitorResponse(201, null)),
                m.getCreatedAt().toString(), "Monitor");
    }

    /** Every query parameter that is not a paging knob is an equality filter, dotted paths allowed. */
    private boolean matches(JsonNode view, Map<String, String> params) {
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

    private boolean valueMatches(JsonNode node, String[] path, int i, String expected) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            for (JsonNode n : node) {
                if (valueMatches(n, path, i, expected)) {
                    return true;
                }
            }
            return false;
        }
        if (i == path.length) {
            return node.isValueNode() && expected.equals(node.asText());
        }
        if (node.isObject()) {
            return valueMatches(node.get(path[i]), path, i + 1, expected);
        }
        return false;
    }

    /* ---------- helpers ---------- */

    private static String customerOf(JsonNode dto) {
        JsonNode parties = dto.get("relatedParty");
        if (parties == null || !parties.isArray()) {
            return null;
        }
        for (JsonNode party : parties) {
            if ("customer".equals(party.path("role").asText(null)) && party.hasNonNull("id")) {
                return party.get("id").asText();
            }
        }
        return null;
    }

    private static Optional<OffsetDateTime> parseDate(JsonNode raw) {
        if (raw == null || !raw.isTextual() || raw.asText().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(raw.asText()).truncatedTo(ChronoUnit.MILLIS));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode o) {
        return o != null && o.isTextual() && !o.asText().isBlank() ? o.asText() : null;
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

    private ObjectNode readJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode n = objectMapper.readTree(json);
            return n != null && n.isObject() ? (ObjectNode) n : null;
        } catch (Exception e) {
            return null;
        }
    }
}
