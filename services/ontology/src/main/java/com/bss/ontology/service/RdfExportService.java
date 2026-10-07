package com.bss.ontology.service;

import com.bss.ontology.registry.Registry;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/**
 * The registry as RDF (Turtle) for consumers that speak the semantic-web
 * dialect — TR326-style knowledge graphs, ontology tooling, a triple store if an
 * operator runs one. Generated from the same YAML; the YAML stays the source of
 * truth. Concepts become classes, actions become individuals of ga:Action with
 * their preconditions as blank nodes, capabilities and components likewise;
 * SID/TMF lineage rides as annotations.
 */
@Service
public class RdfExportService {

    public static final String NS = "https://genalpha.example/ontology#";

    private final Registry registry;

    public RdfExportService(Registry registry) {
        this.registry = registry;
    }

    public String turtle(String tenant) {
        Registry.Layer l = registry.forTenant(tenant);
        StringBuilder t = new StringBuilder();
        t.append("@prefix ga: <").append(NS).append("> .\n");
        t.append("@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n");
        t.append("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n");
        t.append("@prefix owl: <http://www.w3.org/2002/07/owl#> .\n");
        t.append("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\n");
        t.append("<").append(NS.substring(0, NS.length() - 1)).append("> a owl:Ontology ;\n")
                .append("  rdfs:label \"GenAlpha Operational Ontology\" ;\n")
                .append("  rdfs:comment ").append(lit("Generated from the Operational Semantic Registry for tenant " + tenant
                        + ". The registry (YAML) is the source of truth; this export is derived.")).append(" .\n\n");
        t.append("ga:Concept a owl:Class . ga:Action a owl:Class . ga:Capability a owl:Class . ga:Component a owl:Class . ga:Precondition a owl:Class . ga:Event a owl:Class .\n\n");
        for (JsonNode c : l.concepts().values()) {
            String id = "ga:" + c.path("concept").asString();
            t.append(id).append(" a owl:Class, ga:Concept ;\n");
            t.append("  rdfs:label ").append(lit(c.path("concept").asString())).append(" ;\n");
            t.append("  rdfs:comment ").append(lit(c.path("meaning").asString())).append(" ;\n");
            t.append("  ga:version ").append(c.path("version").asInt()).append(" ;\n");
            t.append("  ga:sidLineage ").append(lit(c.path("lineage").path("sid").asString())).append(" ;\n");
            for (JsonNode tmf : c.path("lineage").path("tmf")) {
                t.append("  ga:tmfLineage ").append(lit(tmf.asString())).append(" ;\n");
            }
            if (c.path("lineage").has("oda")) {
                t.append("  ga:odaLineage ").append(lit(c.path("lineage").path("oda").asString())).append(" ;\n");
            }
            t.append("  ga:heldBy ga:").append(safe(l.capabilities().get(c.path("backedBy").path("capability").asString()).path("component").asString())).append(" ;\n");
            t.append("  ga:stateField ").append(lit(c.path("states").path("field").asString())).append(" ;\n");
            for (JsonNode s : c.path("states").path("values")) {
                t.append("  ga:state ").append(lit(s.asString())).append(" ;\n");
            }
            for (JsonNode link : c.path("links")) {
                t.append("  ga:link [ ga:name ").append(lit(link.path("name").asString())).append(" ; ga:to ga:").append(link.path("to").asString())
                        .append(" ; ga:via ").append(lit(link.path("via").asString())).append(" ] ;\n");
            }
            for (JsonNode ev : c.path("events")) {
                t.append("  ga:event ga:").append(ev.path("event").asString()).append(" ;\n");
            }
            trimEnd(t).append(" .\n\n");
        }
        for (JsonNode comp : l.components().values()) {
            t.append("ga:").append(safe(comp.path("component").asString())).append(" a ga:Component ;\n");
            t.append("  rdfs:label ").append(lit(comp.path("component").asString())).append(" ;\n");
            t.append("  rdfs:comment ").append(lit(comp.path("meaning").asString())).append(" ;\n");
            if (comp.has("oda")) {
                t.append("  ga:odaLineage ").append(lit(comp.path("oda").asString())).append(" ;\n");
            }
            for (JsonNode ev : comp.path("events")) {
                t.append("  ga:emits ga:").append(ev.asString()).append(" ;\n");
                t.append("  ga:eventTopic ").append(lit(comp.path("topic").asString(""))).append(" ;\n");
            }
            trimEnd(t).append(" .\n");
            for (JsonNode ev : comp.path("events")) {
                t.append("ga:").append(ev.asString()).append(" a ga:Event ; ga:producedBy ga:").append(safe(comp.path("component").asString())).append(" .\n");
            }
            t.append('\n');
        }
        for (JsonNode ag : l.agents().values()) {
            t.append("ga:").append(safe(ag.path("agent").asString())).append(" a ga:Agent ;\n");
            t.append("  rdfs:label ").append(lit(ag.path("agent").asString())).append(" ;\n");
            t.append("  rdfs:comment ").append(lit(ag.path("meaning").asString())).append(" ;\n");
            t.append("  ga:agentKind ").append(lit(ag.path("kind").asString())).append(" ;\n");
            t.append("  ga:runsAs ").append(lit(ag.path("runsAs").asString())).append(" ;\n");
            t.append("  ga:autonomy ").append(lit(ag.path("autonomy").asString())).append(" ;\n");
            for (JsonNode r : ag.path("reads")) {
                t.append("  ga:mayRead ga:").append(safe(r.asString())).append(" ;\n");
            }
            for (JsonNode x : ag.path("actions").path("check")) {
                t.append("  ga:mayCheck ga:").append(safe(x.asString())).append(" ;\n");
            }
            for (JsonNode x : ag.path("actions").path("execute")) {
                t.append("  ga:mayExecute ga:").append(safe(x.asString())).append(" ;\n");
            }
            for (JsonNode u : ag.path("uses")) {
                t.append("  ga:usesModelFor ").append(lit(u.asString())).append(" ;\n");
            }
            trimEnd(t).append(" .\n\n");
        }
        for (JsonNode cap : l.capabilities().values()) {
            t.append("ga:").append(safe(cap.path("id").asString())).append(" a ga:Capability ;\n");
            t.append("  rdfs:label ").append(lit(cap.path("id").asString())).append(" ;\n");
            t.append("  rdfs:comment ").append(lit(cap.path("meaning").asString())).append(" ;\n");
            t.append("  ga:kind ").append(lit(cap.path("kind").asString())).append(" ;\n");
            t.append("  ga:component ga:").append(safe(cap.path("component").asString())).append(" ;\n");
            if (cap.has("tmf")) {
                t.append("  ga:tmfLineage ").append(lit(cap.path("tmf").asString())).append(" ;\n");
            }
            if (cap.has("route")) {
                t.append("  ga:route ").append(lit(cap.path("route").path("method").asString() + " " + cap.path("route").path("path").asString())).append(" ;\n");
            }
            trimEnd(t).append(" .\n");
        }
        t.append('\n');
        for (JsonNode a : l.actions().values()) {
            t.append("ga:").append(a.path("action").asString()).append(" a ga:Action ;\n");
            t.append("  rdfs:label ").append(lit(ExplainService.title(a.path("action").asString()))).append(" ;\n");
            t.append("  rdfs:comment ").append(lit(a.path("meaning").asString())).append(" ;\n");
            t.append("  ga:version ").append(a.path("version").asInt()).append(" ;\n");
            t.append("  ga:status ").append(lit(a.path("status").asString())).append(" ;\n");
            if (a.has("supersededBy")) {
                t.append("  ga:supersededBy ga:").append(a.path("supersededBy").asString()).append(" ;\n");
            }
            t.append("  ga:actsOn ga:").append(a.path("concept").asString()).append(" ;\n");
            for (JsonNode in : a.path("inputs")) {
                t.append("  ga:input [ ga:name ").append(lit(in.path("name").asString())).append(" ; ga:type ").append(lit(in.path("type").asString()))
                        .append(in.has("concept") ? " ; ga:refersTo ga:" + in.path("concept").asString() : "").append(" ] ;\n");
            }
            for (JsonNode pc : a.path("preconditions")) {
                t.append("  ga:precondition [ a ga:Precondition ; ga:id ").append(lit(pc.path("id").asString())).append(" ; ga:check ")
                        .append(lit(pc.path("check").asString())).append(" ; ga:says ").append(lit(pc.path("says").asString())).append(" ] ;\n");
            }
            for (JsonNode clause : a.path("permissions").path("anyOf")) {
                t.append("  ga:permittedTo ").append(lit(clause.has("self") ? "self:" + clause.path("self").asString() : "role:" + clause.path("role").asString())).append(" ;\n");
            }
            if (a.has("policy")) {
                t.append("  ga:policyDomain ").append(lit(a.path("policy").path("domain").asString())).append(" ;\n");
            }
            t.append("  ga:autonomy ").append(lit(a.path("governance").path("autonomy").asString())).append(" ;\n");
            t.append("  ga:approval ").append(lit(a.path("governance").path("approval").asString())).append(" ;\n");
            t.append("  ga:executes ga:").append(safe(a.path("executes").path("capability").asString())).append(" ;\n");
            for (JsonNode e : a.path("effects")) {
                t.append("  ga:effect ga:").append(safe(e.path("capability").asString())).append(" ;\n");
            }
            for (JsonNode e : a.path("emits")) {
                t.append("  ga:emits ga:").append(e.path("event").asString()).append(" ;\n");
            }
            trimEnd(t).append(" .\n\n");
        }
        return t.toString();
    }

    private static StringBuilder trimEnd(StringBuilder t) {
        int i = t.lastIndexOf(" ;\n");
        if (i == t.length() - 3) {
            t.setLength(i);
        }
        return t;
    }

    private static String safe(String id) {
        return id.replace('.', '_').replace('-', '_');
    }

    private static String lit(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
