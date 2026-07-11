package com.bss.som.service;

import com.bss.som.entity.Intent;
import com.bss.som.entity.ResourcePool;
import com.bss.som.events.DomainEventPublisher;
import com.bss.som.exception.BadRequestException;
import com.bss.som.exception.NotFoundException;
import com.bss.som.repository.IntentRepository;
import com.bss.som.repository.ResourcePoolRepository;
import com.bss.som.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The intent loop of the AI-slice story: business intent in (latency
 * budget, bandwidth, place, AI capacity), autonomous feasibility out.
 * The physics rule is the heart of it — a sub-20ms round trip cannot be
 * served from a distant cloud, so low-latency intents are only feasible
 * where an edge GPU pool covers the place. And the network does not just
 * say yes: when edge capacity exists, it PROPOSES AI inferencing as an
 * upsell even if the customer only asked for connectivity.
 */
@Service
public class IntentService {

    /** Below this round-trip budget, physics forces the workload to the edge. */
    private static final long EDGE_LATENCY_THRESHOLD_MS = 20;
    private static final long MAX_BANDWIDTH_MBPS = 10000;
    public static final String EDGE_GPU = "edge-gpu";

    private final IntentRepository intents;
    private final ResourcePoolRepository pools;
    private final DomainEventPublisher events;
    private final TenantScope tenantScope;
    private final ObjectMapper objectMapper;

    public IntentService(IntentRepository intents, ResourcePoolRepository pools,
            DomainEventPublisher events, TenantScope tenantScope, ObjectMapper objectMapper) {
        this.intents = intents;
        this.pools = pools;
        this.events = events;
        this.tenantScope = tenantScope;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        Map<String, Object> expression = dto.get("expression") instanceof Map<?, ?> e
                ? castMap(e) : dto;
        if (dto.get("name") == null || expression.get("place") == null
                || expression.get("latencyMs") == null) {
            throw new BadRequestException(
                    "name and expression {place, latencyMs, bandwidthMbps} are required");
        }
        Intent intent = new Intent();
        intent.setId(UUID.randomUUID().toString());
        intent.setTenantId(tenantScope.currentTenantId());
        intent.setName(String.valueOf(dto.get("name")));
        intent.setDescription(dto.get("description") == null ? null
                : String.valueOf(dto.get("description")));
        if (dto.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                && parties.get(0) instanceof Map<?, ?> party && party.get("id") != null) {
            intent.setOwnerPartyId(String.valueOf(party.get("id")));
        }
        intent.setPlace(String.valueOf(expression.get("place")));
        intent.setLatencyMs(asLong(expression.get("latencyMs"), "latencyMs"));
        intent.setBandwidthMbps(expression.get("bandwidthMbps") == null ? 1000
                : asLong(expression.get("bandwidthMbps"), "bandwidthMbps"));
        intent.setAiTokensMillions(expression.get("aiTokensMillions") == null ? null
                : asLong(expression.get("aiTokensMillions"), "aiTokensMillions"));
        intent.setValidFrom(parseTime(expression.get("validFrom")));
        intent.setValidUntil(parseTime(expression.get("validUntil")));
        intent.setStatus(Intent.ACKNOWLEDGED);
        intent.setCreatedAt(OffsetDateTime.now());
        intent.setLastUpdate(OffsetDateTime.now());

        // Autonomous feasibility: no human between the ask and the answer.
        Map<String, Object> report = evaluate(intent);
        intent.setStatus(Boolean.TRUE.equals(report.get("feasible"))
                ? Intent.FEASIBILITY_CHECKED : Intent.INFEASIBLE);
        try {
            intent.setReport(objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            throw new IllegalStateException("report serialization failed", e);
        }
        intents.save(intent);
        Map<String, Object> result = toMap(intent);
        events.publish("IntentCreateEvent", "intent", result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findAll() {
        return intents.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::toMap).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findById(String id) {
        return toMap(intents.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Intent", id)));
    }

    private Map<String, Object> evaluate(Intent intent) {
        Map<String, Object> report = new LinkedHashMap<>();
        boolean needsEdge = intent.getLatencyMs() < EDGE_LATENCY_THRESHOLD_MS;
        ResourcePool edgePool = pools.findAll().stream()
                .filter(p -> tenantScope.currentTenantId().equals(p.getTenantId()))
                .filter(p -> EDGE_GPU.equals(p.getResourceType()))
                .filter(p -> p.getName().toLowerCase().contains(intent.getPlace().toLowerCase()))
                .findFirst().orElse(null);

        if (intent.getBandwidthMbps() > MAX_BANDWIDTH_MBPS) {
            report.put("feasible", false);
            report.put("reason", "requested bandwidth exceeds slice capability ("
                    + MAX_BANDWIDTH_MBPS + " Mbps)");
            return report;
        }
        if (needsEdge && edgePool == null) {
            report.put("feasible", false);
            report.put("reason", "a " + intent.getLatencyMs() + "ms round trip cannot be served"
                    + " from regional cloud, and no edge GPU site covers '" + intent.getPlace()
                    + "' — physics, not policy");
            return report;
        }

        report.put("feasible", true);
        report.put("deliveryPoint", needsEdge ? "edge:" + edgePool.getName() : "regional-cloud");
        List<Map<String, Object>> proposal = new ArrayList<>();
        proposal.add(Map.of(
                "service", "5g-slice",
                "offeringName", "Stadium 5G Slice",
                "reason", intent.getBandwidthMbps() + " Mbps guaranteed at "
                        + intent.getLatencyMs() + "ms for '" + intent.getPlace() + "'"));
        // The upsell: the network proposes MORE than the customer asked for.
        if (edgePool != null && (needsEdge || intent.getAiTokensMillions() != null)) {
            proposal.add(Map.of(
                    "service", "edge-ai-inferencing",
                    "offeringName", "Edge AI Inferencing",
                    "reason", intent.getAiTokensMillions() != null
                            ? intent.getAiTokensMillions() + "M tokens of AI inferencing next to the venue"
                            : "GPU capacity is available at " + edgePool.getName()
                                    + " — AI workloads (overlays, highlights, commentary) can run"
                                    + " inside the latency budget; proposed as an extension"));
        }
        report.put("proposedItems", proposal);
        report.put("expectation", Map.of(
                "latencyMs", intent.getLatencyMs(),
                "bandwidthMbps", intent.getBandwidthMbps(),
                "slaBacked", true));
        return report;
    }

    private Map<String, Object> toMap(Intent intent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", intent.getId());
        map.put("href", "/tmf-api/intentManagement/v4/intent/" + intent.getId());
        map.put("name", intent.getName());
        if (intent.getDescription() != null) map.put("description", intent.getDescription());
        map.put("status", intent.getStatus());
        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("place", intent.getPlace());
        expression.put("latencyMs", intent.getLatencyMs());
        expression.put("bandwidthMbps", intent.getBandwidthMbps());
        if (intent.getAiTokensMillions() != null) {
            expression.put("aiTokensMillions", intent.getAiTokensMillions());
        }
        if (intent.getValidFrom() != null) expression.put("validFrom", intent.getValidFrom().toString());
        if (intent.getValidUntil() != null) expression.put("validUntil", intent.getValidUntil().toString());
        map.put("expression", expression);
        if (intent.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", intent.getOwnerPartyId(), "role", "customer")));
        }
        if (intent.getReport() != null) {
            try {
                map.put("intentReport", objectMapper.readValue(intent.getReport(), Map.class));
            } catch (Exception ignored) {
                // report stays absent if unreadable
            }
        }
        map.put("@type", "Intent");
        return map;
    }

    private static long asLong(Object value, String field) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BadRequestException(field + " must be a number");
        }
    }

    private static OffsetDateTime parseTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
