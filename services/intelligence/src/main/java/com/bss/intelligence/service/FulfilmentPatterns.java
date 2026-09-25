package com.bss.intelligence.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The product copilot proposes a FULFILMENT PATTERN beside the commercial
 * proposal: which customer-facing service (TMF633 CFS) each proposed product
 * specification should name, in words, with what the pattern will and will
 * not do for the product as described. The pick is deterministic — the
 * offering's category first, then the words of the spec and its offerings —
 * so the stub provider and a real model land on the same pattern, and the
 * reason is spelled out. The owner still presses Create (ADR 0012); the
 * assignment itself runs through the governed action assignFulfilmentPattern.
 */
public final class FulfilmentPatterns {

    private FulfilmentPatterns() {
    }

    /** A characteristic the pattern's resource-facing services read and the product does not carry, with the effect in words. */
    public record Missing(String characteristic, String effect) {
    }

    /** The proposed pattern for one product specification. */
    public record Pattern(String cfsId, String cfsName, String family, String reason, List<Missing> missingConsumed) {
    }

    /** How many resource-facing services a pattern relies on — the tie-break when no primary is marked. */
    static int reliesOn(Map<String, Object> cfs) {
        int n = 0;
        for (Map<String, Object> e : list(cfs.get("serviceSpecRelationship"))) {
            if ("reliesOn".equals(e.get("relationshipType"))) {
                n++;
            }
        }
        return n;
    }

    /** The family words the console shows beside a pattern's name. */
    public static final Map<String, String> FAMILY_WORDS = Map.of(
            "mobile", "a network line", "internet", "an install", "tv", "a digital entitlement",
            "device", "a parcel", "partner", "activated with the partner", "security", "a feature toggle",
            "compute", "compute on the edge", "billing-only", "nothing to provision");

    /** Category name (TMF620 offering category) → family; the same table the seeds use. */
    private static final Map<String, String> FAMILY_OF_CATEGORY = Map.of(
            "Mobile plans", "mobile", "Broadband", "internet", "TV & Add-ons", "tv", "Devices", "device",
            "Partner services", "partner", "Security", "security", "Insurance", "billing-only", "Top-ups", "billing-only");

    /** Product-spec characteristics an RFS reads that only make sense on the SPEC (order-time picks such as msisdn are not the product manager's to add). */
    private static final Map<String, String> EFFECT_WHEN_MISSING = Map.of(
            "chargingSpecId", "no charging plan: charging will not be provisioned",
            "zeroRatedApps", "no zero-rated app list: nothing is zero-rated",
            "overageTier", "no overage tiers: usage above the allowance is not rated",
            "sliceProfile", "no slice profile: no network slice",
            "boostHours", "no boost hours: a slice would be permanent, not a pass",
            "deliveryPath", "no delivery path: the slice has no route to ride",
            "accessLayer", "no access layer: wholesale access is ordered at the owner's default layer",
            "speed", "no speed: wholesale access is ordered without a speed");

    /** Attach a pattern to every spec of a parsed copilot proposal; never throws, never blocks the proposal. */
    @SuppressWarnings("unchecked")
    public static void attach(Map<String, Object> parsed, List<Map<String, Object>> serviceSpecs) {
        if (!(parsed.get("proposal") instanceof Map<?, ?> pm) || serviceSpecs == null || serviceSpecs.isEmpty()) {
            return;
        }
        Map<String, Object> proposal = (Map<String, Object>) pm;
        List<Map<String, Object>> specs = list(proposal.get("specs"));
        List<Map<String, Object>> offerings = list(proposal.get("offerings"));
        for (Map<String, Object> spec : specs) {
            List<Map<String, Object>> sold = new ArrayList<>();
            for (Map<String, Object> o : offerings) {
                if (spec.get("ref") != null && spec.get("ref").equals(o.get("specRef"))) {
                    sold.add(o);
                }
            }
            propose(spec, sold, serviceSpecs).ifPresent(p -> spec.put("fulfilmentPattern", toMap(p)));
        }
    }

    /** The pattern for one spec: the offering's category decides, then the words. */
    public static Optional<Pattern> propose(Map<String, Object> spec, List<Map<String, Object>> offerings,
            List<Map<String, Object>> serviceSpecs) {
        String family = null;
        String reason = null;
        for (Map<String, Object> o : offerings) {
            String cat = categoryOf(o);
            if (cat != null && FAMILY_OF_CATEGORY.containsKey(cat)) {
                family = FAMILY_OF_CATEGORY.get(cat);
                reason = "sold under " + cat;
                break;
            }
        }
        if (family == null) {
            StringBuilder words = new StringBuilder(String.valueOf(spec.get("name")));
            for (Map<String, Object> o : offerings) {
                words.append(' ').append(o.get("name")).append(' ').append(o.get("description"));
            }
            String w = words.toString().toLowerCase(Locale.ROOT);
            if (w.matches("(?s).*(insurance|top-up|topup|top up|travel pass).*")) {
                family = "billing-only"; reason = "insurance and top-ups bill and provision nothing";
            } else if (w.matches("(?s).*(fibre|fiber|broadband|internet|dsl).*")) {
                family = "internet"; reason = "the words say a fixed access";
            } else if (w.matches("(?s).*(\\btv\\b|stream|channel pack|screens).*")) {
                family = "tv"; reason = "the words say a digital entitlement";
            } else if (w.matches("(?s).*(handset|smartwatch|watch|phone\\b|device|iphone|galaxy).*")) {
                family = "device"; reason = "the words say a device that ships";
            } else if (w.matches("(?s).*(5g|4g|\\d+\\s?gb|mobile|sim|prepaid|data plan|line).*")) {
                family = "mobile"; reason = "the words say a mobile line";
            } else if (w.matches("(?s).*(partner|entitlement|app subscription).*")) {
                family = "partner"; reason = "the words say a partner's service";
            }
        }
        if (family == null) {
            return Optional.empty();
        }
        // A family may hold more than one pattern: "Mobile line" is the everyday
        // one, "Priority slice" a venue product; "Billing-only product" the
        // everyday one, "Top-up with boost" the exception. Taking the first
        // match proposed whichever the catalog happened to list first, so a
        // 50 GB plan was offered a venue slice. Prefer the pattern the catalog
        // marks as its family's primary (`primaryForFamily`); failing that, the
        // one that declares the most it relies on — a full line beats a single
        // seam — and only then the first, so an unmarked catalog still answers.
        Map<String, Object> cfs = null;
        int best = Integer.MIN_VALUE;
        for (Map<String, Object> s : serviceSpecs) {
            if (!"CFS".equals(s.get("serviceType")) || !family.equals(characteristic(s, "fulfilmentFamily"))) {
                continue;
            }
            int score = ("true".equalsIgnoreCase(characteristic(s, "primaryForFamily")) ? 1000 : 0) + reliesOn(s);
            if (score > best) {
                best = score;
                cfs = s;
            }
        }
        if (cfs == null || idOf(cfs) == null) {
            return Optional.empty();
        }
        return Optional.of(new Pattern(idOf(cfs), String.valueOf(cfs.get("name")), family,
                reason + " — " + cfs.get("name") + ", " + FAMILY_WORDS.getOrDefault(family, family),
                missing(spec, cfs)));
    }

    /** The consumed characteristics the spec lacks, per RFS edge, with the effect in words. */
    static List<Missing> missing(Map<String, Object> spec, Map<String, Object> cfs) {
        List<String> have = new ArrayList<>();
        for (Map<String, Object> c : list(spec.get("productSpecCharacteristic"))) {
            have.add(String.valueOf(c.get("name")));
        }
        List<Missing> out = new ArrayList<>();
        for (Map<String, Object> edge : list(cfs.get("serviceSpecRelationship"))) {
            if (!"reliesOn".equals(edge.get("relationshipType"))) {
                continue;
            }
            String consumes = null;
            boolean optional = false;
            for (Map<String, Object> c : list(edge.get("serviceSpecRelationshipCharacteristic"))) {
                List<Map<String, Object>> vals = list(c.get("serviceSpecCharacteristicValue"));
                String v = vals.isEmpty() ? null : String.valueOf(vals.get(0).get("value"));
                if ("consumes".equals(c.get("name"))) {
                    consumes = v;
                } else if ("required".equals(c.get("name"))) {
                    optional = "false".equalsIgnoreCase(v);
                }
            }
            if (consumes == null) {
                continue;
            }
            // an optional RFS runs only when the product carries what it consumes: the first
            // consumed characteristic is the gate, so that is the one worth telling the owner about
            String[] names = consumes.split(",");
            int upTo = optional ? Math.min(1, names.length) : names.length;
            for (int i = 0; i < upTo; i++) {
                String n = names[i].trim();
                if (!n.isEmpty() && EFFECT_WHEN_MISSING.containsKey(n) && !have.contains(n)) {
                    out.add(new Missing(n, EFFECT_WHEN_MISSING.get(n)));
                }
            }
        }
        return out;
    }

    /** The id off the wire, or null — never the text "null". */
    static String idOf(Map<String, Object> entity) {
        return entity != null && entity.get("id") instanceof String v && !v.isBlank() ? v : null;
    }

    static String categoryOf(Map<String, Object> offering) {
        List<Map<String, Object>> cats = list(offering.get("category"));
        return cats.isEmpty() || cats.get(0).get("name") == null ? null : String.valueOf(cats.get(0).get("name"));
    }

    static String characteristic(Map<String, Object> spec, String name) {
        for (Map<String, Object> c : list(spec.get("serviceSpecCharacteristic"))) {
            if (name.equals(c.get("name"))) {
                List<Map<String, Object>> vals = list(c.get("serviceSpecCharacteristicValue"));
                return vals.isEmpty() ? null : String.valueOf(vals.get(0).get("value"));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> toMap(Pattern p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cfsId", p.cfsId());
        m.put("cfsName", p.cfsName());
        m.put("family", p.family());
        m.put("reason", p.reason());
        List<Map<String, Object>> miss = new ArrayList<>();
        for (Missing x : p.missingConsumed()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("characteristic", x.characteristic());
            mm.put("effect", x.effect());
            miss.add(mm);
        }
        m.put("missingConsumed", miss);
        return m;
    }
}
