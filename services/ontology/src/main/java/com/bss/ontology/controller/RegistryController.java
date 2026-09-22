package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.dto.ConformanceResult;
import com.bss.ontology.dto.CustomerContext;
import com.bss.ontology.dto.CustomerRecommendations;
import com.bss.ontology.dto.Explanation;
import com.bss.ontology.dto.RecommendationOutcome;
import com.bss.ontology.dto.RegistryOverview;
import com.bss.ontology.exception.NotFoundException;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.Caller;
import com.bss.ontology.service.ConformanceService;
import com.bss.ontology.service.ContextService;
import com.bss.ontology.service.ExplainService;
import com.bss.ontology.service.RdfExportService;
import com.bss.ontology.service.RecommendationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The registry, read: the merged core + tenant view for the caller's tenant, and the same in words. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class RegistryController {

    private final Registry registry;
    private final ExplainService explain;
    private final TenantScope tenantScope;
    private final RdfExportService rdf;
    private final ContextService context;
    private final ConformanceService conformanceService;
    private final RecommendationService recommendationService;

    public RegistryController(Registry registry, ExplainService explain, TenantScope tenantScope,
            RdfExportService rdf, ContextService context, ConformanceService conformanceService,
            RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
        this.conformanceService = conformanceService;
        this.registry = registry;
        this.explain = explain;
        this.tenantScope = tenantScope;
        this.rdf = rdf;
        this.context = context;
    }

    @GetMapping
    public RegistryOverview overview() {
        String tenant = tenantScope.currentTenantId();
        Registry.Layer l = registry.forTenant(tenant);
        return new RegistryOverview("GenAlpha Operational Ontology", "Operational Semantic Registry", tenant,
                registry.tenantsWithOverlay().contains(tenant), l.concepts().size(), l.actions().size(), l.capabilities().size(),
                l.components().size(), l.agents().size(),
                List.of("TM Forum semantics (lineage)", "GenAlpha core", "operator extensions"),
                "AI reasons and proposes. Policies govern. Deterministic components execute.");
    }

    @GetMapping("/concepts")
    public List<JsonNode> concepts() {
        return new ArrayList<>(registry.forTenant(tenantScope.currentTenantId()).concepts().values());
    }

    @GetMapping("/concepts/{name}")
    public JsonNode concept(@PathVariable String name) {
        JsonNode c = registry.forTenant(tenantScope.currentTenantId()).concepts().get(name);
        if (c == null) {
            throw NotFoundException.forResource("concept", name);
        }
        return c;
    }

    @GetMapping("/actions")
    public List<JsonNode> actions() {
        return new ArrayList<>(registry.forTenant(tenantScope.currentTenantId()).actions().values());
    }

    @GetMapping("/actions/{name}")
    public JsonNode action(@PathVariable String name) {
        JsonNode a = registry.forTenant(tenantScope.currentTenantId()).actions().get(name);
        if (a == null) {
            throw NotFoundException.forResource("action", name);
        }
        return a;
    }

    @GetMapping("/capabilities")
    public List<JsonNode> capabilities() {
        return new ArrayList<>(registry.forTenant(tenantScope.currentTenantId()).capabilities().values());
    }

    /** The agents: every named AI actor, whose rights it runs with, what it may read, check and execute. */
    @GetMapping("/agents")
    public List<JsonNode> agents() {
        return new ArrayList<>(registry.forTenant(tenantScope.currentTenantId()).agents().values());
    }

    @GetMapping("/agents/{name}")
    public JsonNode agent(@PathVariable String name) {
        JsonNode a = registry.forTenant(tenantScope.currentTenantId()).agents().get(name);
        if (a == null) {
            throw new NotFoundException("no agent named " + name);
        }
        return a;
    }

    @GetMapping("/explain/agent/{name}")
    public Explanation explainAgent(@PathVariable String name) {
        Explanation e = explain.agent(name, tenantScope.currentTenantId());
        if (e == null) {
            throw new NotFoundException("no agent named " + name);
        }
        return e;
    }

    @GetMapping("/components")
    public List<JsonNode> components() {
        return new ArrayList<>(registry.forTenant(tenantScope.currentTenantId()).components().values());
    }

    /** Declaration against reality for one component, or all of them. */
    @GetMapping("/components/{name}/conformance")
    public ConformanceResult conformance(@PathVariable String name) {
        return conformanceService.component(name, tenantScope.currentTenantId());
    }

    @GetMapping("/conformance")
    public List<ConformanceResult> conformanceAll() {
        return conformanceService.all(tenantScope.currentTenantId());
    }

    @GetMapping("/schemas")
    public Map<String, JsonNode> schemas() {
        return registry.schemas();
    }

    @GetMapping("/explain/action/{name}")
    public Explanation explainAction(@PathVariable String name) {
        Explanation m = explain.action(name, tenantScope.currentTenantId());
        if (m == null) {
            throw NotFoundException.forResource("action", name);
        }
        return m;
    }

    @GetMapping("/explain/journey/{name}")
    public Explanation explainJourney(@PathVariable String name) {
        Explanation m = explain.journey(name, tenantScope.currentTenantId());
        if (m == null) {
            throw NotFoundException.forResource("action", name);
        }
        return m;
    }

    @GetMapping("/explain/concept/{name}")
    public Explanation explainConcept(@PathVariable String name) {
        Explanation m = explain.concept(name, tenantScope.currentTenantId());
        if (m == null) {
            throw NotFoundException.forResource("concept", name);
        }
        return m;
    }

    /** The registry as RDF/Turtle for semantic-web consumers; derived, never the source of truth. */
    @GetMapping(value = "/export.ttl", produces = "text/turtle;charset=UTF-8")
    public String turtle() {
        return rdf.turtle(tenantScope.currentTenantId());
    }

    /** One call: a customer's sub-graph for an agent, walked with the caller's rights. */
    @GetMapping("/context/customer/{id}")
    public CustomerContext customerContext(@PathVariable String id, HttpServletRequest request) {
        return context.customer(id, Caller.current(request, tenantScope.currentTenantId()));
    }

    /** What should I do next for this customer — governed actions dry-run through the registry, and things to explain. */
    @GetMapping("/context/customer/{id}/recommendations")
    public CustomerRecommendations recommendations(@PathVariable String id, HttpServletRequest request) {
        return recommendationService.forCustomer(id, Caller.current(request, tenantScope.currentTenantId()));
    }

    /** What the agent did with a recommendation — accepted, dismissed, helpful, unhelpful — so the ranking learns. */
    @PostMapping("/context/recommendations/{decisionId}/outcome")
    public RecommendationOutcome recommendationOutcome(@PathVariable String decisionId,
            @RequestBody(required = false) RecommendationOutcome.Request body, HttpServletRequest request) {
        RecommendationOutcome.Request b = body == null ? RecommendationOutcome.Request.EMPTY : body;
        try {
            return recommendationService.outcome(decisionId, b.outcomeOrEmpty(), b.reason(),
                    Caller.current(request, tenantScope.currentTenantId()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Console page paths may carry a slash (simulate/priceChange): the rest of the path is the page. */
    @GetMapping("/explain/page/**")
    public Explanation explainPage(HttpServletRequest request) {
        String prefix = ApiConstants.BASE_PATH + "/explain/page/";
        String path = request.getRequestURI().substring(request.getRequestURI().indexOf(prefix) + prefix.length());
        return explain.page(path, tenantScope.currentTenantId());
    }
}
