package com.bss.som.service;

import com.bss.som.dto.IntentDtos.Expectation;
import com.bss.som.dto.IntentDtos.Expression;
import com.bss.som.dto.IntentDtos.ExpressionView;
import com.bss.som.dto.IntentDtos.IntentReport;
import com.bss.som.dto.IntentDtos.IntentRequest;
import com.bss.som.dto.IntentDtos.IntentView;
import com.bss.som.dto.IntentDtos.ProposedItem;
import com.bss.som.dto.PartyRef;
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
    public IntentView create(IntentRequest dto) {
        // the expression may ride bare at the top level (the keys the record kept)
        Expression expression = dto.expression() != null ? dto.expression()
                : objectMapper.convertValue(dto.extensions(), Expression.class);
        if (dto.name() == null || expression.place() == null || expression.latencyMs() == null) {
            throw new BadRequestException(
                    "name and expression {place, latencyMs, bandwidthMbps} are required");
        }
        Intent intent = new Intent();
        intent.setId(UUID.randomUUID().toString());
        intent.setTenantId(tenantScope.currentTenantId());
        intent.setName(dto.name());
        intent.setDescription(dto.description());
        if (dto.relatedParty() != null && !dto.relatedParty().isEmpty()
                && dto.relatedParty().get(0) != null && dto.relatedParty().get(0).id() != null) {
            intent.setOwnerPartyId(dto.relatedParty().get(0).id());
        }
        intent.setPlace(expression.place());
        intent.setLatencyMs(expression.latencyMs());
        intent.setBandwidthMbps(expression.bandwidthMbps() == null ? 1000 : expression.bandwidthMbps());
        intent.setAiTokensMillions(expression.aiTokensMillions());
        intent.setValidFrom(parseTime(expression.validFrom()));
        intent.setValidUntil(parseTime(expression.validUntil()));
        intent.setStatus(Intent.ACKNOWLEDGED);
        intent.setCreatedAt(OffsetDateTime.now());
        intent.setLastUpdate(OffsetDateTime.now());

        // Autonomous feasibility: no human between the ask and the answer.
        IntentReport report = evaluate(intent);
        intent.setStatus(report.feasible() ? Intent.FEASIBILITY_CHECKED : Intent.INFEASIBLE);
        try {
            intent.setReport(objectMapper.writeValueAsString(report));
        } catch (Exception e) {
            throw new IllegalStateException("report serialization failed", e);
        }
        intents.save(intent);
        IntentView result = view(intent);
        events.publish("IntentCreateEvent", "intent", result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<IntentView> findAll() {
        return intents.findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public IntentView findById(String id) {
        return view(intents.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("Intent", id)));
    }

    private IntentReport evaluate(Intent intent) {
        boolean needsEdge = intent.getLatencyMs() < EDGE_LATENCY_THRESHOLD_MS;
        ResourcePool edgePool = pools.findAll().stream()
                .filter(p -> tenantScope.currentTenantId().equals(p.getTenantId()))
                .filter(p -> EDGE_GPU.equals(p.getResourceType()))
                .filter(p -> p.getName().toLowerCase().contains(intent.getPlace().toLowerCase()))
                .findFirst().orElse(null);

        if (intent.getBandwidthMbps() > MAX_BANDWIDTH_MBPS) {
            return IntentReport.infeasible("requested bandwidth exceeds slice capability ("
                    + MAX_BANDWIDTH_MBPS + " Mbps)");
        }
        if (needsEdge && edgePool == null) {
            return IntentReport.infeasible("a " + intent.getLatencyMs() + "ms round trip cannot be served"
                    + " from regional cloud, and no edge GPU site covers '" + intent.getPlace()
                    + "' — physics, not policy");
        }

        List<ProposedItem> proposal = new ArrayList<>();
        proposal.add(new ProposedItem("5g-slice", "Stadium 5G Slice",
                intent.getBandwidthMbps() + " Mbps guaranteed at "
                        + intent.getLatencyMs() + "ms for '" + intent.getPlace() + "'"));
        // The upsell: the network proposes MORE than the customer asked for.
        if (edgePool != null && (needsEdge || intent.getAiTokensMillions() != null)) {
            proposal.add(new ProposedItem("edge-ai-inferencing", "Edge AI Inferencing",
                    intent.getAiTokensMillions() != null
                            ? intent.getAiTokensMillions() + "M tokens of AI inferencing next to the venue"
                            : "GPU capacity is available at " + edgePool.getName()
                                    + " — AI workloads (overlays, highlights, commentary) can run"
                                    + " inside the latency budget; proposed as an extension"));
        }
        return new IntentReport(true, null, needsEdge ? "edge:" + edgePool.getName() : "regional-cloud", proposal,
                new Expectation(intent.getLatencyMs(), intent.getBandwidthMbps(), true));
    }

    /** The stored report is read back as the tree it was written as — rows older images wrote included. */
    private IntentView view(Intent intent) {
        com.fasterxml.jackson.databind.JsonNode report = null;
        if (intent.getReport() != null) {
            try {
                report = objectMapper.readTree(intent.getReport());
            } catch (Exception ignored) {
                // report stays absent if unreadable
            }
        }
        return new IntentView(intent.getId(), "/tmf-api/intentManagement/v4/intent/" + intent.getId(),
                intent.getName(), intent.getDescription(), intent.getStatus(),
                new ExpressionView(intent.getPlace(), intent.getLatencyMs(), intent.getBandwidthMbps(),
                        intent.getAiTokensMillions(),
                        intent.getValidFrom() == null ? null : intent.getValidFrom().toString(),
                        intent.getValidUntil() == null ? null : intent.getValidUntil().toString()),
                PartyRef.customerListOrNull(intent.getOwnerPartyId()), report, "Intent");
    }

    private static OffsetDateTime parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

}
