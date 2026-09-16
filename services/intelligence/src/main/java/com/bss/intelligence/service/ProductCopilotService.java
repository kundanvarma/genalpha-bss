package com.bss.intelligence.service;

import com.bss.intelligence.exception.BadRequestException;
import com.bss.intelligence.llm.LlmAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The product copilot: a product owner CHATS about the product they want to
 * sell; the copilot explains how it models in TMF620 and, when asked,
 * returns a complete PROPOSAL — specs, prices (conditioned ones included),
 * offerings, categories, bundles. The copilot only ever proposes: the
 * console validates the proposal, shows it as a human-readable card, and a
 * deterministic executor applies it with the product owner's OWN token when
 * they confirm. The model never holds credentials and never writes.
 *
 * Same privilege stance as the CSR copilot: the console sends the catalog
 * context the owner's token can already see; nothing is fetched here.
 */
@Service
public class ProductCopilotService {

    private static final int CONTEXT_CHARS = 4000;
    private static final int HISTORY_TURNS = 12;

    private final LlmAdapter llm;
    private final Redactor redactor;
    private final com.bss.intelligence.llm.AiGovernor governor;
    private final ObjectMapper objectMapper;
    private final com.bss.intelligence.llm.TenantVoice voice;
    private final com.bss.intelligence.sim.PriceSimService priceSim;
    private final com.bss.intelligence.client.BssApiClient bssApi;

    public ProductCopilotService(LlmAdapter llm, Redactor redactor,
            com.bss.intelligence.llm.AiGovernor governor, ObjectMapper objectMapper,
            com.bss.intelligence.llm.TenantVoice voice,
            com.bss.intelligence.sim.PriceSimService priceSim,
            com.bss.intelligence.client.BssApiClient bssApi) {
        this.llm = llm;
        this.redactor = redactor;
        this.governor = governor;
        this.objectMapper = objectMapper;
        this.voice = voice;
        this.priceSim = priceSim;
        this.bssApi = bssApi;
    }

    // Deliberately NOT @Transactional: when the model misses the contract we
    // throw AFTER auditing both attempts, and a wrapping transaction would
    // roll the audit rows back with the failure — the ledger must keep
    // exactly the turns that failed.
    @SuppressWarnings("unchecked")
    public Map<String, Object> chat(Map<String, Object> request) {
        if (!(request.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
            throw new BadRequestException("messages [{role, content}] are required");
        }
        String system = """
                You are the product copilot of a TM Forum ODA telecom BSS. A product owner \
                describes a product in plain language; you help them model and create it. \
                The modeling rules of this catalog:
                - An OFFERING is what is sold; a SPECIFICATION describes what it is. \
                Zero-rated apps ("social media does not count", "free WhatsApp") are a spec \
                characteristic named "zeroRatedApps" with configurable false and the app list as \
                its value (e.g. "WhatsApp, Instagram, TikTok") — the OCS zero-rates them at activation. \
                Configurable characteristics (color, storage) become pickers in the shop; \
                characteristics with "configurable": false render as an About-facts table.
                - CATEGORIES drive placement AND fulfilment: "Mobile plans" and "Broadband" \
                activate network lines with numbers and SIMs; "Devices" ship hardware; \
                "Partner services" mint partner entitlement codes (Netflix-style); \
                "Security" activates a feature; "Insurance" and "Top-ups" only bill; \
                "TV & Add-ons" for TV; "Bundles" for bundles.
                - PRICES: priceType "recurring" with recurringChargePeriodType "month", or \
                "oneTime". A price with prodSpecCharValueUse applies only to matching \
                configurations (e.g. a colour premium) — never one offering per variant.
                - BUNDLES: isBundle true + bundledProductOffering children; a child with \
                bundledProductOfferingOption {numberRelOfferLowerLimit: 0, numberRelOfferUpperLimit: 1} \
                is optional. A commitment is productOfferingTerm \
                [{name, duration: {amount: 12, units: "month"}}].
                - A BUNDLE CHILD KEEPS ITS OWN STANDALONE PRICE. To sell an existing \
                product cheaper alongside a plan ("Netflix for 5 with this plan"), do NOT \
                add a price to the plan — propose a pricingRule with a negative "amount" \
                equal to the reduction, whenCartHas both products.
                - CROSS-PRODUCT DISCOUNTS ("10% off the phone when bought with this plan") \
                are PRICING RULES, not prices: propose them as pricingRules entries with \
                whenCartHas listing the offerings that must be in the cart together \
                (a ref from this proposal, or the exact name of an existing offering). \
                adjustmentValue is negative for a discount.
                Respond with ONLY a JSON object, no markdown fences, shaped:
                {"kind": "question"|"advice"|"proposal", "message": "<what you say to the owner>", \
                "proposal": null or {"specs": [{"ref": "s1", "name", "brand"?, "productSpecCharacteristic": \
                [{"name": "homeLocations", "configurable": true, "productSpecCharacteristicValue": [{"value": "1-2"}, {"value": "3-4"}]}]}], \
                "prices": [{"ref": "p1", "name", "priceType", "recurringChargePeriodType"?, \
                "price": {"unit", "value"}, "prodSpecCharValueUse"?, "unitOfMeasure"?: {"amount", "units"}, \
                "validFor"?: {"startDateTime", "endDateTime"}, "pricingLogicAlgorithm"?: [{"plaSpecId": \
                "perUnitAbove"|"stepped", "characteristic", "threshold"?, "unitPrice"?, "tier"?: [{"valueFrom", "valueTo", "price"}]}]}], \
                "offerings": [{"ref": "o1", "name", "description", "category": [{"name"}], \
                "specRef"?, "priceRefs": [], "isBundle"?, "productOfferingTerm"?, \
                "validFor"?: {"startDateTime": ISO, "endDateTime"?: ISO} (when the owner names a \
                launch date or a campaign window), "channel"?: ["web","app","store","telesales",\
                "care","business","partner","agent-acp","agent-mcp","agent-a2a"] (ONLY the channels \
                the owner names; omit = every channel), \
                "bundledChildren"?: [{"offeringRef" or "existingName", "optional": true|false}], \
                "relationships"?: [{"relationshipType": "requires"|"excludes"|"exchangableTo", "offeringRef" or \
                "existingName", "role"?: "auto-add"|"prompt"|"block"}]}], \
                "pricingRules": [{"name", "message", "adjustmentType": "percent"|"amount", \
                "adjustmentValue": -10, "whenCartHas": ["o1", "Samsung Galaxy S26"], \
                "audience": "all"|"consumer"|"business"}], \
                "experienceRules": [{"name", "whenInterest": "<catalog category the guest \
                browsed>", "banner": "<what the shop says to them>", \
                "pinOffering"?: "o1" or the exact name of an existing offering}]}
                audience "consumer" limits a discount to private customers (it will not \
                apply to company purchases or consolidated business invoices).
                PERSONALIZATION (experienceRules) changes what a CONSENTING guest SEES — \
                banner copy and one pinned offering when their browsing interest matches a \
                category — never a price; discounts stay in pricingRules.
                A proposal MUST contain at least one offering — specs and prices alone sell \
                nothing (proposals that only add pricingRules or experienceRules to \
                EXISTING offerings are the exceptions). Every price needs an offering whose \
                priceRefs uses it. \
                Example of a complete minimal proposal: {"kind":"proposal","message":"...", \
                "proposal":{"specs":[{"ref":"s1","name":"City Plan","productSpecCharacteristic":[]}], \
                "prices":[{"ref":"p1","name":"City Plan Monthly","priceType":"recurring", \
                "recurringChargePeriodType":"month","price":{"unit":"EUR","value":19.99}}], \
                "offerings":[{"ref":"o1","name":"City Plan","description":"...", \
                "category":[{"name":"Mobile plans"}],"specRef":"s1","priceRefs":["p1"]}]}}
                HARD RULES: prices are ALWAYS positive — NEVER express a discount as a \
                price, discounts go ONLY in pricingRules. whenCartHas entries must be refs \
                from this proposal or names copied EXACTLY from the catalog context — never \
                substitute a different product than the owner named. A binding period \
                ("6 month binding") is productOfferingTerm on the offering, not a price. \
                prodSpecCharValueUse must be a LIST of objects like \
                [{"name": "color", "productSpecCharacteristicValue": [{"value": "Titanium"}]}] \
                — OMIT it entirely unless the price depends on a configured characteristic; \
                never use it for bundle membership or descriptions.
                MORE SHAPES, each with the standard's own field: a NUMERIC CHOICE ("up to 10 extra \
                profiles, 10 per profile above two") is a characteristic whose productSpecCharacteristicValue \
                is a RANGE [{"valueFrom": 0, "valueTo": 10}] and a price with pricingLogicAlgorithm \
                [{"plaSpecId": "perUnitAbove", "characteristic": "extraProfiles", "threshold": 2, "unitPrice": 10}]; \
                PER-SEAT or PER-LICENCE pricing is a price with "unitOfMeasure": {"amount": 1, "units": "seat"} \
                and a spec fact {"name": "fungible", "configurable": false, "productSpecCharacteristicValue": [{"value": "true"}]} \
                (units are interchangeable, sold in quantity on one line — never for lines with a number or a SIM); \
                a LIMITED-TIME price line is a price with "validFor"; an EARLY-TERMINATION charge is a price with \
                "priceType": "penalty" and "unitOfMeasure": {"amount": <term months>, "units": "month"} next to the \
                offering's productOfferingTerm (it declines monthly and is never charged on the configuration); \
                "needs X" is a relationship {"relationshipType": "requires", "existingName": "X", "role": "prompt"} \
                and "cannot be combined with Y" is {"relationshipType": "excludes", "existingName": "Y"}; \
                "customers can move to Z" is {"relationshipType": "exchangableTo", "existingName": "Z"} (the like-for-like \
                change list every channel offers). Catalog context lists EVERY offering name under "names"; use them verbatim. \
                A CONFIGURABLE PRODUCT ("the customer picks the number of screens / locations / \
                devices") is ONE offering whose spec has one configurable characteristic per choice \
                with its allowed values as productSpecCharacteristicValue [{"value": ...}] (never a \
                "values" array), a base price, and one conditioned price per surcharge whose \
                prodSpecCharValueUse names that characteristic and value. The included tier has no price.
                Ask a question when the ask is ambiguous; give advice when they want to \
                understand; produce a proposal when they ask you to create or they have \
                answered your questions. Use the tenant's existing categories and currency. \
                Keep names short and sellable.""" + voice.instruction();

        StringBuilder conversation = new StringBuilder();
        Object catalog = request.get("catalog");
        if (catalog != null) {
            conversation.append("Catalog context (what exists today):\n")
                    .append(cap(toJson(catalog))).append("\n---\n");
        }
        List<Map<String, Object>> turns = (List<Map<String, Object>>) messages;
        for (Map<String, Object> turn : turns.subList(Math.max(0, turns.size() - HISTORY_TURNS), turns.size())) {
            conversation.append("owner".equalsIgnoreCase(String.valueOf(turn.get("role"))) || "user".equalsIgnoreCase(String.valueOf(turn.get("role")))
                    ? "OWNER: " : "COPILOT: ");
            conversation.append(redactor.redact(String.valueOf(turn.getOrDefault("content", "")))).append("\n");
        }

        String raw = governor.complete("product-copilot",
                com.bss.intelligence.llm.LlmAdapter.Tier.SMART, system, conversation.toString());
        Map<String, Object> parsed = parse(raw);
        if (parsed == null) {
            raw = governor.complete("product-copilot-retry",
                    com.bss.intelligence.llm.LlmAdapter.Tier.SMART, system, conversation
                    + "\nYour previous answer was not the required bare JSON object. Respond again"
                    + " with ONLY the JSON object described in the instructions.");
            parsed = parse(raw);
        }
        if (parsed == null) {
            throw new BadRequestException("the model did not follow the copilot JSON contract");
        }
        parsed.put("provider", llm.provider());
        parsed.put("model", llm.model());
        normalize(parsed);
        attachForecast(parsed);
        return parsed;
    }

    /**
     * P2 — FORECAST RECEIPTS: an AI proposal that reprices an EXISTING
     * offering gets scored by the commercial simulator BEFORE the owner sees
     * the Approve button. An AI proposal without a forecast is just an
     * opinion. Fail-soft: a forecast that cannot be computed attaches
     * nothing — it never blocks the proposal itself.
     */
    @SuppressWarnings("unchecked")
    private void attachForecast(Map<String, Object> parsed) {
        try {
            if (!"proposal".equals(String.valueOf(parsed.get("kind")))
                    || !(parsed.get("proposal") instanceof Map<?, ?> proposal)) {
                return;
            }
            List<Map<String, Object>> offerings = proposal.get("offerings") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            List<Map<String, Object>> prices = proposal.get("prices") instanceof List<?> l
                    ? (List<Map<String, Object>>) l : List.of();
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (Map<String, Object> o : bssApi.offerings()) {
                existing.add(String.valueOf(o.get("name")));
            }
            List<Map<String, Object>> changes = new java.util.ArrayList<>();
            for (Map<String, Object> offering : offerings) {
                String name = String.valueOf(offering.get("name"));
                if (!existing.contains(name)) {
                    continue;   // a NEW offering has no base to move — nothing to simulate
                }
                // the offering's new monthly = its UNCONDITIONED recurring prices summed; surcharges that only
                // apply to a configuration are not what every subscriber pays, so they never enter the forecast
                double monthly = 0;
                boolean any = false;
                for (Object refObj : offering.get("priceRefs") instanceof List<?> refs ? refs : List.of()) {
                    String ref = String.valueOf(refObj);
                    for (Map<String, Object> price : prices) {
                        if (ref.equals(String.valueOf(price.get("ref")))
                                && "recurring".equals(String.valueOf(price.get("priceType")))
                                && !(price.get("prodSpecCharValueUse") instanceof List<?> c && !c.isEmpty())
                                && price.get("price") instanceof Map<?, ?> p && p.get("value") != null) {
                            try {
                                monthly += Double.parseDouble(String.valueOf(p.get("value")));
                                any = true;
                            } catch (NumberFormatException ignored) {
                                // a price the model wrote as prose — the validator reports it
                            }
                        }
                    }
                }
                if (any) {
                    changes.add(Map.of("offeringName", name, "newMonthlyPrice", monthly));
                }
            }
            if (changes.isEmpty()) {
                return;
            }
            Map<String, Object> report = priceSim.simulate(Map.of(
                    "name", "copilot proposal forecast",
                    "changes", changes));
            // a forecast over nobody is noise, not a receipt: keep only lines with subscribers
            if (report != null && report.get("lines") instanceof List<?> lines) {
                List<Object> kept = new java.util.ArrayList<>();
                for (Object l : lines) {
                    if (l instanceof Map<?, ?> m) {
                        try {
                            if (Double.parseDouble(String.valueOf(m.get("subscribers"))) > 0) {
                                kept.add(l);
                            }
                        } catch (NumberFormatException ignored) {
                            // no subscriber count — not a line worth showing
                        }
                    }
                }
                if (kept.isEmpty()) {
                    return;
                }
                Map<String, Object> trimmed = new java.util.LinkedHashMap<>(report);
                trimmed.put("lines", kept);
                report = trimmed;
            }
            parsed.put("forecast", report);
        } catch (RuntimeException e) {
            // the forecast is a receipt, not a gate — its absence is visible, not fatal
        }
    }

    /**
     * Deterministic repair of the model's known habits BEFORE anything reads the proposal: a
     * characteristic's allowed values arrive as "values": ["1-2", ...] or as bare strings rather
     * than TMF's productSpecCharacteristicValue [{value}], a condition arrives as one object
     * instead of a list, and the choices a price conditions on are missing from the spec. The
     * shop can only offer a choice the spec declares, so the spec is completed from the prices.
     */
    @SuppressWarnings("unchecked")
    static void normalize(Map<String, Object> parsed) {
        if (!(parsed.get("proposal") instanceof Map<?, ?> pm)) {
            return;
        }
        Map<String, Object> proposal = (Map<String, Object>) pm;
        List<Map<String, Object>> specs = listOf(proposal.get("specs"));
        List<Map<String, Object>> prices = listOf(proposal.get("prices"));
        List<Map<String, Object>> offerings = listOf(proposal.get("offerings"));
        for (Map<String, Object> spec : specs) {
            List<Map<String, Object>> chars = listOf(spec.get("productSpecCharacteristic"));
            for (Map<String, Object> c : chars) {
                Object rawValues = c.containsKey("productSpecCharacteristicValue") ? c.get("productSpecCharacteristicValue")
                        : c.containsKey("values") ? c.get("values")
                        : c.containsKey("allowedValues") ? c.get("allowedValues")
                        : c.get("value");
                List<Map<String, Object>> values = valuesOf(rawValues);
                c.remove("values");
                c.remove("allowedValues");
                c.remove("value");
                c.put("productSpecCharacteristicValue", values);
                if (c.get("configurable") == null) {
                    c.put("configurable", values.size() > 1);
                }
            }
            spec.put("productSpecCharacteristic", chars);
        }
        for (Map<String, Object> price : prices) {
            Object cond = price.get("prodSpecCharValueUse");
            if (cond instanceof Map<?, ?> one) {
                price.put("prodSpecCharValueUse", new java.util.ArrayList<>(List.of(one)));
            }
            for (Map<String, Object> c : listOf(price.get("prodSpecCharValueUse"))) {
                Object rawValues = c.containsKey("productSpecCharacteristicValue") ? c.get("productSpecCharacteristicValue")
                        : c.containsKey("values") ? c.get("values") : c.get("value");
                c.remove("values");
                c.remove("value");
                c.put("productSpecCharacteristicValue", valuesOf(rawValues));
            }
        }
        // every characteristic an algorithm counts must exist on the offering's spec, as a numeric range
        for (Map<String, Object> offering : offerings) {
            Map<String, Object> spec = null;
            for (Map<String, Object> s : specs) {
                if (String.valueOf(s.get("ref")).equals(String.valueOf(offering.get("specRef")))) {
                    spec = s;
                }
            }
            if (spec == null) {
                continue;
            }
            List<Map<String, Object>> chars = listOf(spec.get("productSpecCharacteristic"));
            for (Object refObj : offering.get("priceRefs") instanceof List<?> refs ? refs : List.of()) {
                for (Map<String, Object> price : prices) {
                    if (!String.valueOf(refObj).equals(String.valueOf(price.get("ref")))) {
                        continue;
                    }
                    for (Map<String, Object> pla : listOf(price.get("pricingLogicAlgorithm"))) {
                        String name = String.valueOf(pla.getOrDefault("characteristic", "quantity"));
                        if ("quantity".equals(name)) {
                            continue;
                        }
                        Map<String, Object> target = null;
                        for (Map<String, Object> c : chars) {
                            if (name.equals(String.valueOf(c.get("name")))) {
                                target = c;
                            }
                        }
                        if (target == null) {
                            target = new java.util.LinkedHashMap<>();
                            target.put("name", name);
                            chars.add(target);
                        }
                        target.put("configurable", true);
                        target.put("valueType", "number");
                        List<Map<String, Object>> have = listOf(target.get("productSpecCharacteristicValue"));
                        if (have.isEmpty()) {
                            Map<String, Object> range = new java.util.LinkedHashMap<>();
                            range.put("valueFrom", 0);
                            range.put("valueTo", pla.get("valueTo") != null ? pla.get("valueTo") : 10);
                            range.put("rangeInterval", "closed");
                            have.add(range);
                        }
                        target.put("productSpecCharacteristicValue", have);
                    }
                }
            }
            spec.put("productSpecCharacteristic", chars);
        }
        // every choice a price conditions on must exist on the offering's spec, with that value
        for (Map<String, Object> offering : offerings) {
            Map<String, Object> spec = null;
            for (Map<String, Object> s : specs) {
                if (String.valueOf(s.get("ref")).equals(String.valueOf(offering.get("specRef")))) {
                    spec = s;
                }
            }
            if (spec == null) {
                continue;
            }
            List<Map<String, Object>> chars = listOf(spec.get("productSpecCharacteristic"));
            for (Object refObj : offering.get("priceRefs") instanceof List<?> refs ? refs : List.of()) {
                for (Map<String, Object> price : prices) {
                    if (!String.valueOf(refObj).equals(String.valueOf(price.get("ref")))) {
                        continue;
                    }
                    for (Map<String, Object> cond : listOf(price.get("prodSpecCharValueUse"))) {
                        String name = String.valueOf(cond.get("name"));
                        Map<String, Object> target = null;
                        for (Map<String, Object> c : chars) {
                            if (name.equals(String.valueOf(c.get("name")))) {
                                target = c;
                            }
                        }
                        if (target == null) {
                            target = new java.util.LinkedHashMap<>();
                            target.put("name", name);
                            target.put("configurable", true);
                            target.put("productSpecCharacteristicValue", new java.util.ArrayList<>());
                            chars.add(target);
                        }
                        target.put("configurable", true);
                        List<Map<String, Object>> have = listOf(target.get("productSpecCharacteristicValue"));
                        for (Map<String, Object> v : listOf(cond.get("productSpecCharacteristicValue"))) {
                            String val = String.valueOf(v.get("value"));
                            if (have.stream().noneMatch(h -> val.equals(String.valueOf(h.get("value"))))) {
                                Map<String, Object> nv = new java.util.LinkedHashMap<>();
                                nv.put("value", val);
                                have.add(nv);
                            }
                        }
                        target.put("productSpecCharacteristicValue", have);
                    }
                }
            }
            spec.put("productSpecCharacteristic", chars);
        }
        proposal.put("specs", specs);
        proposal.put("prices", prices);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map<?, ?> m) {
                    out.add(new java.util.LinkedHashMap<>((Map<String, Object>) m));
                }
            }
        }
        return out;
    }

    /** Allowed values in TMF shape from whatever the model wrote: a list of {value}, a list of strings, one string. */
    private static List<Map<String, Object>> valuesOf(Object raw) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (raw == null) {
            return out;
        }
        List<?> items = raw instanceof List<?> l ? l : List.of(raw);
        for (Object x : items) {
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            if (x instanceof Map<?, ?> m) {
                Object val = m.get("value") != null ? m.get("value") : m.get("name");
                boolean range = m.get("valueFrom") != null || m.get("valueTo") != null;
                if (val == null && !range) {
                    continue;
                }
                v.putAll((Map<String, Object>) m);
                if (val != null) {
                    v.put("value", String.valueOf(val));
                }
            } else {
                v.put("value", String.valueOf(x));
            }
            out.add(v);
        }
        return out;
    }

    /** Markdown-tolerant JSON parse: strip fences, find the outermost object. */
    private Map<String, Object> parse(String raw) {
        if (raw == null) {
            return null;
        }
        String body = raw.trim().replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)```\\s*$", "");
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(body.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
            String kind = String.valueOf(parsed.get("kind"));
            if (!List.of("question", "advice", "proposal").contains(kind) || parsed.get("message") == null) {
                return null;
            }
            if ("proposal".equals(kind) && !(parsed.get("proposal") instanceof Map)) {
                // mechanical repair for a common small-model miss: the
                // proposal's parts emitted at the TOP level with proposal null
                Map<String, Object> lifted = new java.util.LinkedHashMap<>();
                for (String key : List.of("specs", "prices", "offerings", "pricingRules",
                        "experienceRules")) {
                    if (parsed.get(key) instanceof List<?> list && !list.isEmpty()) {
                        lifted.put(key, list);
                    }
                }
                if (lifted.isEmpty()) {
                    return null;
                }
                parsed.put("proposal", lifted);
            }
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException("catalog context is not serializable");
        }
    }

    private String cap(String s) {
        return s.length() <= CONTEXT_CHARS ? s : s.substring(0, CONTEXT_CHARS);
    }

}
