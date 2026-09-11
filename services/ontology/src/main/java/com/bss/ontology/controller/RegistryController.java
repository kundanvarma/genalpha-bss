package com.bss.ontology.controller;

import com.bss.ontology.api.ApiConstants;
import com.bss.ontology.exception.NotFoundException;
import com.bss.ontology.registry.Registry;
import com.bss.ontology.security.TenantScope;
import com.bss.ontology.service.ExplainService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The registry, read: the merged core + tenant view for the caller's tenant, and the same in words. */
@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class RegistryController {

    private final Registry registry;
    private final ExplainService explain;
    private final TenantScope tenantScope;
    private final com.bss.ontology.service.RdfExportService rdf;
    private final com.bss.ontology.service.ContextService context;
    private final com.bss.ontology.service.ConformanceService conformanceService;
    private final com.bss.ontology.service.RecommendationService recommendationService;

    public RegistryController(Registry registry, ExplainService explain, TenantScope tenantScope,
            com.bss.ontology.service.RdfExportService rdf, com.bss.ontology.service.ContextService context,
            com.bss.ontology.service.ConformanceService conformanceService,
            com.bss.ontology.service.RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
        this.conformanceService = conformanceService;
        this.registry = registry;
        this.explain = explain;
        this.tenantScope = tenantScope;
        this.rdf = rdf;
        this.context = context;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Registry.Layer l = registry.forTenant(tenantScope.currentTenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", "GenAlpha Operational Ontology");
        out.put("registry", "Operational Semantic Registry");
        out.put("tenant", tenantScope.currentTenantId());
        out.put("tenantOverlay", registry.tenantsWithOverlay().contains(tenantScope.currentTenantId()));
        out.put("concepts", l.concepts().size());
        out.put("actions", l.actions().size());
        out.put("capabilities", l.capabilities().size());
        out.put("components", l.components().size());
        out.put("layers", List.of("TM Forum semantics (lineage)", "GenAlpha core", "operator extensions"));
        out.put("rule", "AI reasons and proposes. Policies govern. Deterministic components execute.");
        return out;
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

    @GetMapping("/components")
    public List<JsonNode> components() {
        return new ArrayList<>(registry.forTenant(tenantScope.currentTenantId()).components().values());
    }

    /** Declaration against reality for one component, or all of them. */
    @GetMapping("/components/{name}/conformance")
    public Map<String, Object> conformance(@PathVariable String name) {
        return conformanceService.component(name, tenantScope.currentTenantId());
    }

    @GetMapping("/conformance")
    public List<Map<String, Object>> conformanceAll() {
        return conformanceService.all(tenantScope.currentTenantId());
    }

    @GetMapping("/schemas")
    public Map<String, JsonNode> schemas() {
        return registry.schemas();
    }

    @GetMapping("/explain/action/{name}")
    public Map<String, Object> explainAction(@PathVariable String name) {
        Map<String, Object> m = explain.action(name, tenantScope.currentTenantId());
        if (m == null) {
            throw NotFoundException.forResource("action", name);
        }
        return m;
    }

    @GetMapping("/explain/journey/{name}")
    public Map<String, Object> explainJourney(@PathVariable String name) {
        Map<String, Object> m = explain.journey(name, tenantScope.currentTenantId());
        if (m == null) {
            throw NotFoundException.forResource("action", name);
        }
        return m;
    }

    @GetMapping("/explain/concept/{name}")
    public Map<String, Object> explainConcept(@PathVariable String name) {
        Map<String, Object> m = explain.concept(name, tenantScope.currentTenantId());
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
    public Map<String, Object> customerContext(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        return context.customer(id, com.bss.ontology.service.Caller.current(request, tenantScope.currentTenantId()));
    }

    /** What should I do next for this customer — governed actions dry-run through the registry, and things to explain. */
    @GetMapping("/context/customer/{id}/recommendations")
    public Map<String, Object> recommendations(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        return recommendationService.forCustomer(id, com.bss.ontology.service.Caller.current(request, tenantScope.currentTenantId()));
    }

    /** Console page paths may carry a slash (simulate/priceChange): the rest of the path is the page. */
    @GetMapping("/explain/page/**")
    public Map<String, Object> explainPage(jakarta.servlet.http.HttpServletRequest request) {
        String prefix = ApiConstants.BASE_PATH + "/explain/page/";
        String path = request.getRequestURI().substring(request.getRequestURI().indexOf(prefix) + prefix.length());
        return explain.page(path, tenantScope.currentTenantId());
    }
}
