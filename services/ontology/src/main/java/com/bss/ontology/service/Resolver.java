package com.bss.ontology.service;

import com.bss.ontology.client.ComponentClient;
import com.bss.ontology.registry.Registry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the objects an action's inputs point at, with the caller's own token —
 * so a customer resolves their own line and nobody else's. Each ref input
 * becomes a resolved object named after the input ("subscriptionId" →
 * "subscription"); a Subscription pulls its owner, current offering, the line
 * (service) and the customer along, because every check on it needs them.
 */
@Component
public class Resolver {

    public static final class Resolved {
        public final Map<String, JsonNode> objects = new LinkedHashMap<>();
        public final Map<String, ComponentClient.Reply> replies = new LinkedHashMap<>();
        public final List<String> problems = new ArrayList<>();
        public final Map<String, BigDecimal> monthly = new LinkedHashMap<>();

        public JsonNode get(String key) {
            return objects.get(key);
        }

        public boolean has(String key) {
            JsonNode n = objects.get(key);
            return n != null && !n.isMissingNode() && !n.isNull();
        }
    }

    private final Registry registry;
    private final ComponentClient client;
    private final ObjectMapper json;

    public Resolver(Registry registry, ComponentClient client, ObjectMapper json) {
        this.registry = registry;
        this.client = client;
        this.json = json;
    }

    public static String keyOf(String inputName) {
        return inputName.endsWith("Id") ? inputName.substring(0, inputName.length() - 2) : inputName;
    }

    public Resolved resolve(JsonNode action, Map<String, String> inputs, Caller caller) {
        Registry.Layer layer = registry.forTenant(caller.tenant());
        Resolved r = new Resolved();
        for (JsonNode in : action.path("inputs")) {
            String name = in.path("name").asText();
            String id = inputs.get(name);
            if (id == null || id.isBlank()) {
                if (in.path("required").asBoolean(false)) {
                    r.problems.add("input " + name + " is required");
                }
                continue;
            }
            if (!"ref".equals(in.path("type").asText())) {
                continue;
            }
            String conceptName = in.path("concept").asText();
            JsonNode concept = layer.concepts().get(conceptName);
            String key = keyOf(name);
            if ("ProductOffering".equals(conceptName)) {
                // the target offering is read through the caller's channel — what is not on sale there does not exist for them
                load(layer, concept, key, id, caller, Map.of(Caller.CHANNEL_HEADER, caller.channel()), r);
                if (!r.has(key)) {
                    load(layer, concept, key + "AnyChannel", id, caller, Map.of(), r);
                }
            } else {
                load(layer, concept, key, id, caller, Map.of(), r);
            }
            if ("Subscription".equals(conceptName) && r.has(key)) {
                enrichSubscription(layer, key, caller, r);
            } else if (conceptName.equals(action.path("concept").asText()) && r.has(key) && !r.has("owner")) {
                // the party behind the acted-on object: relatedParty[role=customer], else the first party
                ObjectNode owner = json.createObjectNode();
                owner.put("id", ownerOf(r.get(key)));
                r.objects.put("owner", owner);
            }
        }
        return r;
    }

    static String ownerOf(JsonNode obj) {
        String id = "";
        for (JsonNode p : obj.path("relatedParty")) {
            if (id.isEmpty() || "customer".equalsIgnoreCase(p.path("role").asText())) {
                id = p.path("id").asText();
            }
        }
        if (id.isEmpty()) {
            id = obj.path("ownerPartyId").asText(obj.path("partyId").asText(""));
        }
        return id;
    }

    private void load(Registry.Layer layer, JsonNode concept, String key, String id, Caller caller,
            Map<String, String> headers, Resolved r) {
        if (concept == null) {
            r.problems.add("unknown concept for " + key);
            return;
        }
        JsonNode cap = layer.capabilities().get(concept.path("backedBy").path("capability").asText());
        ComponentClient.Reply reply = client.call(cap.path("component").asText(), cap.path("route").path("method").asText(),
                cap.path("route").path("path").asText(), Map.of("id", id), Map.of(), null, caller.bearer(), headers);
        r.replies.put(key, reply);
        if (reply.ok()) {
            r.objects.put(key, reply.body());
        } else {
            r.problems.add(key + " could not be read (" + statusWords(reply) + ")");
        }
    }

    private void enrichSubscription(Registry.Layer layer, String key, Caller caller, Resolved r) {
        JsonNode sub = r.get(key);
        String ownerId = null;
        for (JsonNode p : sub.path("relatedParty")) {
            if (ownerId == null || "customer".equalsIgnoreCase(p.path("role").asText())) {
                ownerId = p.path("id").asText();
            }
        }
        ObjectNode owner = json.createObjectNode();
        owner.put("id", ownerId == null ? "" : ownerId);
        r.objects.put("owner", owner);
        String offeringId = sub.path("productOffering").path("id").asText();
        if (!offeringId.isEmpty()) {
            load(layer, layer.concepts().get("ProductOffering"), "currentOffering", offeringId, caller, Map.of(), r);
        }
        // the line: TMF638 service of the owner with the product's name (matched by name — the known weak seam)
        JsonNode cap = layer.capabilities().get("serviceInventory.services");
        if (cap != null && ownerId != null) {
            ComponentClient.Reply reply = client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                    Map.of(), Map.of("relatedPartyId", ownerId, "limit", "100"), null, caller.bearer(), Map.of());
            r.replies.put("services", reply);
            if (reply.ok() && reply.body().isArray()) {
                JsonNode chosen = null;
                for (JsonNode s : reply.body()) {
                    boolean sameName = s.path("name").asText().equals(sub.path("name").asText());
                    boolean live = "active".equalsIgnoreCase(s.path("state").asText());
                    if (sameName && (chosen == null || live)) {
                        chosen = s;
                    }
                }
                if (chosen != null) {
                    r.objects.put("service", chosen);
                }
            }
        }
        JsonNode party = layer.capabilities().get("party.individual");
        if (party != null && ownerId != null) {
            ComponentClient.Reply reply = client.call(party.path("component").asText(), "GET", party.path("route").path("path").asText(),
                    Map.of("id", ownerId), Map.of(), null, caller.bearer(), Map.of());
            r.replies.put("customer", reply);
            if (reply.ok()) {
                r.objects.put("customer", reply.body());
            }
        }
    }

    /** The recurring price of an offering, read through its price references; null when it has none. */
    public BigDecimal monthlyOf(String key, Resolved r, Caller caller) {
        if (r.monthly.containsKey(key)) {
            return r.monthly.get(key);
        }
        JsonNode offering = r.get(key);
        BigDecimal found = null;
        if (offering != null) {
            Registry.Layer layer = registry.forTenant(caller.tenant());
            JsonNode cap = layer.capabilities().get("productCatalog.price");
            for (JsonNode ref : offering.path("productOfferingPrice")) {
                String id = ref.path("id").asText();
                if (id.isEmpty()) {
                    continue;
                }
                ComponentClient.Reply reply = client.call(cap.path("component").asText(), "GET", cap.path("route").path("path").asText(),
                        Map.of("id", id), Map.of(), null, caller.bearer(), Map.of());
                if (reply.ok() && "recurring".equalsIgnoreCase(reply.body().path("priceType").asText())) {
                    JsonNode v = reply.body().path("price").path("value");
                    if (v.isNumber() || v.isTextual()) {
                        found = new BigDecimal(v.asText());
                        break;
                    }
                }
            }
        }
        r.monthly.put(key, found);
        return found;
    }

    /** The customer's postcode, from a postal contact medium, or "" when none is on file. */
    public static String postCodeOf(JsonNode customer) {
        if (customer == null) {
            return "";
        }
        for (JsonNode cm : customer.path("contactMedium")) {
            String type = cm.path("mediumType").asText();
            if (type.toLowerCase().contains("postal") || type.toLowerCase().contains("address")) {
                JsonNode ch = cm.path("characteristic");
                String pc = ch.path("postCode").asText(ch.path("postcode").asText(""));
                if (!pc.isEmpty()) {
                    return pc;
                }
            }
        }
        return "";
    }

    static String statusWords(ComponentClient.Reply reply) {
        return switch (reply.status()) {
            case 401 -> "not signed in";
            case 403 -> "not allowed to read it";
            case 404 -> "not found";
            case -1 -> "the component did not answer";
            default -> "status " + reply.status();
        };
    }
}
