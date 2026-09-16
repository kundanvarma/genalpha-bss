package com.bss.catalog.service;

import com.bss.catalog.client.PolicyClient;
import com.bss.catalog.client.StockReader;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TMF760 Product Configuration — THE oracle every channel asks. Given an
 * offering and the customer's picks it answers with the configuration
 * space (choices, allowed values and ranges, defaults, what is in stock),
 * whether a pick set is orderable (both bundle bounds, allowed values,
 * relationships, availability, the same policy rules the order gate runs)
 * and what it costs (every price that applies, per unit times quantity,
 * windows honoured, named algorithms evaluated), plus an order-ready
 * configuration. Channels render this; none of them price or validate on
 * their own, so the shop, the app, the desk, the consoles and the agents
 * cannot disagree about a product.
 *
 * Shape: TMF760 v5 item names and states (`accepted | rejected`,
 * `stateReason[{code, label}]`, a `ProductConfiguration` with
 * `configurationCharacteristic[]`, `configurationPrice[]`,
 * `configurationAction[]`, `quantity`), plus the house extension
 * `priceSummary` and the flat `message[]` earlier clients read.
 */
@Service
public class ConfiguratorService {

    private static final Logger log = LoggerFactory.getLogger(ConfiguratorService.class);

    /** The documented pricing algorithms a price may name in pricingLogicAlgorithm[].plaSpecId. */
    static final String PLA_PER_UNIT_ABOVE = "perUnitAbove";
    static final String PLA_STEPPED = "stepped";

    private final ProductOfferingService offerings;
    private final ProductOfferingPriceService prices;
    private final ProductSpecificationService specs;
    private final PolicyClient policy;
    private final StockReader stock;

    public ConfiguratorService(ProductOfferingService offerings, ProductOfferingPriceService prices,
            ProductSpecificationService specs, PolicyClient policy, StockReader stock) {
        this.offerings = offerings;
        this.prices = prices;
        this.specs = specs;
        this.policy = policy;
        this.stock = stock;
    }

    /* =====================================================================
     * queryProductConfiguration: the configuration SPACE as data.
     * ===================================================================== */

    @Transactional(readOnly = true)
    public Map<String, Object> query(Map<String, Object> request) {
        String offeringId = offeringIdOf(request);
        ProductOfferingDto offering = offerings.findById(offeringId);
        Map<String, Object> availability = stock.availability(offeringId);

        // ---- the house view (what the first clients read)
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productOffering", ref(offering));
        item.put("isBundle", Boolean.TRUE.equals(offering.getIsBundle()));
        List<Map<String, Object>> fixedMembers = new ArrayList<>();
        List<Map<String, Object>> choiceGroups = new ArrayList<>();
        for (Map<String, Object> member : listOf(offering.getBundledProductOffering())) {
            if (isChoiceGroup(member)) {
                choiceGroups.add(choiceGroupView(member));
            } else {
                fixedMembers.add(fixedMemberView(member));
            }
        }
        if (!fixedMembers.isEmpty()) {
            item.put("fixedMember", fixedMembers);
        }
        if (!choiceGroups.isEmpty()) {
            item.put("choiceGroup", choiceGroups);
        }
        List<Map<String, Object>> ownChars = configurableCharacteristicsOf(offering, availability);
        if (!ownChars.isEmpty()) {
            item.put("configurationCharacteristic", ownChars);
        }
        List<Map<String, Object>> ownPrices = priceViewsOf(offering);
        if (!ownPrices.isEmpty()) {
            item.put("price", ownPrices);
        }
        if (offering.getProductOfferingTerm() != null && !offering.getProductOfferingTerm().isEmpty()) {
            item.put("productOfferingTerm", offering.getProductOfferingTerm());
        }
        List<Map<String, Object>> relationships = relationshipViews(offering);
        if (!relationships.isEmpty()) {
            item.put("productOfferingRelationship", relationships);
        }
        item.put("fungible", isFungible(offering));
        if (!availability.isEmpty()) {
            item.put("availability", availability);
        }
        item.put("@type", "ComputedProductConfiguration");

        // ---- the v5 item: the same facts in the standard's shape
        Map<String, Object> v5 = new LinkedHashMap<>();
        v5.put("id", "1");
        v5.put("state", "accepted");
        v5.put("productConfiguration", v5Configuration(offering, ownChars, ownPrices, relationships, fixedMembers, choiceGroups));
        v5.put("@type", "QueryProductConfigurationItem");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", offeringId);
        out.put("state", "done");
        out.put("instantSync", true);
        out.put("queryProductConfigurationItem", List.of(v5));
        out.put("computedProductConfigurationItem", List.of(item));
        out.put("@type", "QueryProductConfiguration");
        return out;
    }

    /* =====================================================================
     * checkProductConfiguration: is THIS pick set orderable, what does it
     * cost, and what must ride along.
     * ===================================================================== */

    @Transactional(readOnly = true)
    public Map<String, Object> check(Map<String, Object> request) {
        List<Map<String, Object>> items = listOf(request.get("checkProductConfigurationItem"));
        if (items.isEmpty()) {
            throw new BadRequestException("checkProductConfigurationItem is required");
        }
        List<Map<String, Object>> outItems = new ArrayList<>();
        boolean allAccepted = true;
        int seq = 0;
        for (Map<String, Object> item : items) {
            seq++;
            Map<String, Object> config = mapOf(item.get("productConfiguration"));
            Map<String, Object> outItem = checkOne(config);
            outItem.put("id", item.get("id") == null ? String.valueOf(seq) : item.get("id"));
            allAccepted = allAccepted && "accepted".equals(outItem.get("state"));
            outItems.add(outItem);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", "done");
        out.put("instantSync", true);
        out.put("result", allAccepted ? "accepted" : "rejected");
        out.put("checkProductConfigurationItem", outItems);
        out.put("@type", "CheckProductConfiguration");
        return out;
    }

    private Map<String, Object> checkOne(Map<String, Object> config) {
        String offeringId = offeringIdOf(config);
        ProductOfferingDto bundle = offerings.findById(offeringId);
        List<String> selected = new ArrayList<>(listOf(config.get("selectedOption")).stream()
                .map(o -> String.valueOf(o.get("id"))).toList());
        Map<String, String> picks = picksOf(config);
        int quantity = intOf(config.get("quantity"), 1);
        // an INSTALLED product is priced as configured: today's stock and today's relationships do not change
        // what a subscriber already holds — billing asks with priceOnly
        boolean priceOnly = Boolean.TRUE.equals(config.get("priceOnly")) || "true".equals(String.valueOf(config.get("priceOnly")));
        List<String> messages = new ArrayList<>();
        List<Map<String, Object>> reasons = new ArrayList<>();
        List<Map<String, Object>> actions = new ArrayList<>();

        // 1. every selected id must actually be part of the bundle
        Set<String> memberIds = new LinkedHashSet<>();
        List<Map<String, Object>> choiceGroups = new ArrayList<>();
        for (Map<String, Object> member : listOf(bundle.getBundledProductOffering())) {
            if (isChoiceGroup(member)) {
                choiceGroups.add(member);
                for (Map<String, Object> opt : listOf(member.get("options"))) {
                    memberIds.add(String.valueOf(opt.get("id")));
                }
            } else {
                memberIds.add(String.valueOf(member.get("id")));
            }
        }
        for (String id : selected) {
            if (!memberIds.contains(id)) {
                reject(messages, reasons, "notAMember", "selected offering '" + id + "' is not part of bundle '" + bundle.getName() + "'");
            }
        }
        // 2. cardinality, BOTH bounds — ordering's wording, configure-time
        for (Map<String, Object> group : choiceGroups) {
            long lower = longOf(group.get("numberRelOfferLowerLimit"), 1);
            long upper = longOf(group.get("numberRelOfferUpperLimit"), 1);
            long chosen = listOf(group.get("options")).stream()
                    .map(o -> String.valueOf(o.get("id"))).filter(selected::contains).count();
            if (chosen < lower || chosen > upper) {
                String need = lower == upper ? "exactly " + lower : "between " + lower + " and " + upper;
                reject(messages, reasons, "cardinality", "bundle '" + bundle.getName() + "': '" + group.get("name")
                        + "' requires " + need + " selection(s), but " + chosen + " were made");
            }
        }
        // 3. characteristic values against the specs the picks brought in (enumerated or ranged)
        List<ProductOfferingDto> selectedOfferings = new ArrayList<>();
        for (String id : selected) {
            if (memberIds.contains(id)) {
                selectedOfferings.add(offerings.findById(id));
            }
        }
        Map<String, List<Map<String, Object>>> allowed = allowedValues(bundle, selectedOfferings);
        for (Map.Entry<String, String> pick : picks.entrySet()) {
            List<Map<String, Object>> values = allowed.get(pick.getKey());
            if (values == null) {
                reject(messages, reasons, "notConfigurable", "characteristic '" + pick.getKey() + "' is not configurable on this configuration");
            } else if (!valueAllowed(values, pick.getValue())) {
                reject(messages, reasons, "valueNotAllowed", "characteristic '" + pick.getKey() + "' value '" + pick.getValue()
                        + "' is not an allowed value (allowed: " + describeAllowed(values) + ")");
            }
        }
        // 4. quantity: only a fungible product is sold in quantity on one line
        if (quantity < 1) {
            reject(messages, reasons, "quantity", "quantity must be at least 1");
        } else if (quantity > 1 && !isFungible(bundle)) {
            reject(messages, reasons, "quantity", "'" + bundle.getName() + "' is sold one per line (each unit has its own identity); order "
                    + quantity + " separate lines instead");
        }
        // 5. relationships: excludes rejects, requires blocks or suggests an action
        for (Map<String, Object> rel : priceOnly ? List.<Map<String, Object>>of() : listOf(bundle.getProductOfferingRelationship())) {
            String type = String.valueOf(rel.getOrDefault("relationshipType", "")).toLowerCase();
            String relId = String.valueOf(rel.get("id"));
            String relName = rel.get("name") == null ? relId : String.valueOf(rel.get("name"));
            String role = String.valueOf(rel.getOrDefault("role", "prompt")).toLowerCase();
            if ("excludes".equals(type) && selected.contains(relId)) {
                reject(messages, reasons, "excludes", "'" + bundle.getName() + "' cannot be combined with '" + relName + "'");
            } else if ("requires".equals(type) && !selected.contains(relId)) {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("action", "add");
                action.put("description", "'" + bundle.getName() + "' requires '" + relName + "'");
                action.put("productOffering", Map.of("id", relId, "name", relName, "@referredType", "ProductOffering"));
                action.put("isSelected", "auto-add".equals(role));
                action.put("role", role);
                actions.add(action);
                if ("block".equals(role)) {
                    reject(messages, reasons, "requires", "'" + bundle.getName() + "' requires '" + relName + "' — add it first");
                }
            }
        }
        // 6. availability: a configured variant that is stock-managed must be in stock
        Map<String, Object> availability = priceOnly ? Map.<String, Object>of() : stock.availability(offeringId);
        String shortage = priceOnly ? null : stock.shortage(availability, picks, quantity);
        if (shortage != null) {
            reject(messages, reasons, "outOfStock", shortage);
        }
        // 7. the same block rules that will guard the order at submit time
        PolicyClient.Verdict verdict = PolicyClient.Verdict.allow();
        if (messages.isEmpty()) {
            verdict = policy.evaluate(policyContext(bundle, selectedOfferings, quantity));
            if (!verdict.allowed()) {
                reject(messages, reasons, "policy", verdict.message());
            }
        }

        Map<String, Object> outItem = new LinkedHashMap<>();
        Map<String, Object> configuration = orderReadyConfiguration(bundle, selectedOfferings, picks, quantity);
        if (messages.isEmpty()) {
            outItem.put("state", "accepted");
            Map<String, Object> priced = priceConfiguration(bundle, selectedOfferings, picks, quantity);
            outItem.put("configurationPrice", priced);
            configuration.put("configurationPrice", priced.get("configurationPrice"));
        } else {
            outItem.put("state", "rejected");
            outItem.put("message", messages);
            outItem.put("stateReason", reasons);
            if (!verdict.allowed() && verdict.ruleName() != null) {
                outItem.put("ruleName", verdict.ruleName());
            }
        }
        if (!actions.isEmpty()) {
            configuration.put("configurationAction", actions);
        }
        outItem.put("productConfiguration", configuration);
        outItem.put("@type", "CheckProductConfigurationItem");
        return outItem;
    }

    private static void reject(List<String> messages, List<Map<String, Object>> reasons, String code, String label) {
        messages.add(label);
        reasons.add(Map.of("code", code, "label", label));
    }

    /** Picks in the house shape ({name, value}) or the v5 shape (selected characteristic values). */
    private static Map<String, String> picksOf(Map<String, Object> config) {
        Map<String, String> picks = new LinkedHashMap<>();
        if (config.get("configurationCharacteristic") instanceof String flat) {
            // the ontology's action inputs are strings: "screens=5+, extraProfiles=6"
            for (String pair : flat.split("[,;]")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    picks.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            return picks;
        }
        for (Map<String, Object> c : listOf(config.get("configurationCharacteristic"))) {
            if (c.get("name") == null) {
                continue;
            }
            if (c.get("value") != null) {
                picks.put(String.valueOf(c.get("name")), String.valueOf(c.get("value")));
                continue;
            }
            for (Map<String, Object> v : listOf(c.get("configurationCharacteristicValue"))) {
                if (Boolean.TRUE.equals(v.get("isSelected")) || listOf(c.get("configurationCharacteristicValue")).size() == 1) {
                    Object val = mapOf(v.get("characteristicValue")).get("value");
                    if (val != null) {
                        picks.put(String.valueOf(c.get("name")), String.valueOf(val));
                    }
                }
            }
        }
        return picks;
    }

    /* ---------- the order-ready echo ---------- */

    private Map<String, Object> orderReadyConfiguration(ProductOfferingDto bundle,
            List<ProductOfferingDto> selectedOfferings, Map<String, String> picks, int quantity) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("productOffering", ref(bundle));
        config.put("quantity", quantity);
        List<Map<String, Object>> options = new ArrayList<>();
        for (ProductOfferingDto offering : selectedOfferings) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", offering.getId());
            option.put("name", offering.getName());
            List<Map<String, Object>> owned = picksOwnedBy(offering, picks);
            if (!owned.isEmpty()) {
                option.put("characteristic", owned);
            }
            options.add(option);
        }
        config.put("selectedOption", options);
        List<Map<String, Object>> ownPicks = picksOwnedBy(bundle, picks);
        if (!ownPicks.isEmpty()) {
            config.put("configurationCharacteristic", ownPicks);
        }
        config.put("@type", "ProductConfiguration");
        return config;
    }

    /** The picks whose name belongs to this offering's own spec and whose value it allows. */
    private List<Map<String, Object>> picksOwnedBy(ProductOfferingDto offering, Map<String, String> picks) {
        List<Map<String, Object>> owned = new ArrayList<>();
        for (Map<String, Object> ch : configurableCharacteristicsOf(offering, Map.of())) {
            String name = String.valueOf(ch.get("name"));
            String picked = picks.get(name);
            if (picked != null && valueAllowed(listOf(ch.get("productSpecCharacteristicValue")), picked)) {
                owned.add(Map.of("name", name, "value", picked));
            }
        }
        return owned;
    }

    /* =====================================================================
     * pricing: every price that applies — unconditioned, conditioned on
     * picks (exact or in range), within its window — per unit times the
     * quantity, named algorithms evaluated. Totals in the tenant's money.
     * ===================================================================== */

    private Map<String, Object> priceConfiguration(ProductOfferingDto bundle,
            List<ProductOfferingDto> selectedOfferings, Map<String, String> picks, int quantity) {
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> v5Lines = new ArrayList<>();
        BigDecimal monthly = BigDecimal.ZERO;
        BigDecimal oneTime = BigDecimal.ZERO;
        String currency = null;
        OffsetDateTime now = OffsetDateTime.now();
        List<ProductOfferingDto> all = new ArrayList<>();
        all.add(bundle);
        all.addAll(selectedOfferings);
        List<Map<String, Object>> terminations = new ArrayList<>();
        for (ProductOfferingDto offering : all) {
            int qty = offering == bundle ? quantity : 1;
            for (Map<String, Object> ref : listOf(offering.getProductOfferingPrice())) {
                ProductOfferingPriceDto price = findPrice(String.valueOf(ref.get("id")));
                if (price == null || !priceApplies(price, picks) || !inWindow(price, now)) {
                    continue;
                }
                if ("penalty".equals(price.getPriceType())) {
                    // never a charge on the configuration: what leaving early would cost, declining over the term
                    Map<String, Object> term = new LinkedHashMap<>();
                    term.put("name", price.getName());
                    term.put("price", price.getPrice());
                    if (price.getUnitOfMeasure() != null) {
                        term.put("declinesOver", price.getUnitOfMeasure());
                    }
                    term.put("says", "leaving early costs at most " + amountOf(price) + " " + (price.getPrice() == null ? "" : price.getPrice().get("unit"))
                            + (price.getUnitOfMeasure() != null ? ", falling by a twelfth each month of the " + price.getUnitOfMeasure().get("amount") + "-" + price.getUnitOfMeasure().get("units") + " term" : ""));
                    terminations.add(term);
                    continue;
                }
                PricedLine pl = evaluate(price, picks, qty);
                if (pl == null) {
                    continue;
                }
                if (currency == null && price.getPrice() != null && price.getPrice().get("unit") != null) {
                    currency = String.valueOf(price.getPrice().get("unit"));
                }
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("offering", Map.of("id", offering.getId(), "name", offering.getName()));
                line.put("name", price.getName());
                line.put("priceType", price.getPriceType());
                line.put("price", price.getPrice());
                if (pl.unitPrice != null) {
                    line.put("unitPrice", pl.unitPrice);
                    line.put("quantity", pl.units);
                }
                line.put("amount", pl.amount);
                if (pl.how != null) {
                    line.put("how", pl.how);
                }
                if (price.getProdSpecCharValueUse() != null && !price.getProdSpecCharValueUse().isEmpty()) {
                    line.put("appliesWhen", price.getProdSpecCharValueUse());
                }
                lines.add(line);
                v5Lines.add(v5Price(price, pl));
                if ("recurring".equals(price.getPriceType())) {
                    monthly = monthly.add(pl.amount);
                } else if ("oneTime".equals(price.getPriceType())) {
                    oneTime = oneTime.add(pl.amount);
                }
            }
        }
        if (currency == null) {
            currency = "EUR";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monthlyTotal", Map.of("unit", currency, "value", monthly));
        out.put("oneTimeTotal", Map.of("unit", currency, "value", oneTime));
        out.put("priceLine", lines);
        out.put("configurationPrice", v5Lines);
        if (!terminations.isEmpty()) {
            out.put("earlyTermination", terminations);
        }
        Map<String, Object> indicative = policy.indicativePrice(indicativeContext(bundle, selectedOfferings, monthly, quantity));
        if (indicative != null) {
            out.put("indicative", indicative);
        }
        out.put("@type", "ConfigurationPrice");
        return out;
    }

    private record PricedLine(BigDecimal amount, BigDecimal unitPrice, int units, String how) {
    }

    /** One price, evaluated: flat; per unit of measure times quantity; or a named algorithm. */
    private PricedLine evaluate(ProductOfferingPriceDto price, Map<String, String> picks, int quantity) {
        BigDecimal value = amountOf(price);
        Map<String, Object> pla = firstAlgorithm(price);
        if (pla != null) {
            String spec = String.valueOf(pla.getOrDefault("plaSpecId", pla.get("name")));
            String characteristic = String.valueOf(pla.getOrDefault("characteristic", "quantity"));
            int count = "quantity".equals(characteristic) ? quantity : intOf(picks.get(characteristic), -1);
            if (count < 0) {
                return null; // the pick the algorithm needs was not made — nothing applies yet
            }
            if (PLA_PER_UNIT_ABOVE.equals(spec)) {
                int threshold = intOf(pla.get("threshold"), 0);
                BigDecimal unit = decimalOf(pla.get("unitPrice"), value);
                int extra = Math.max(0, count - threshold);
                return new PricedLine(unit.multiply(BigDecimal.valueOf(extra)).setScale(2, RoundingMode.HALF_UP), unit, extra,
                        extra + " above " + threshold + " at " + unit + " each");
            }
            if (PLA_STEPPED.equals(spec)) {
                for (Map<String, Object> tier : listOf(pla.get("tier"))) {
                    int from = intOf(tier.get("valueFrom"), Integer.MIN_VALUE);
                    int to = intOf(tier.get("valueTo"), Integer.MAX_VALUE);
                    if (count >= from && count <= to) {
                        BigDecimal tierPrice = decimalOf(tier.get("price"), value);
                        boolean perUnit = !"flat".equals(String.valueOf(tier.getOrDefault("format", "perUnit")));
                        BigDecimal amount = perUnit ? tierPrice.multiply(BigDecimal.valueOf(count)) : tierPrice;
                        return new PricedLine(amount.setScale(2, RoundingMode.HALF_UP), perUnit ? tierPrice : null, count,
                                "tier " + from + "-" + (to == Integer.MAX_VALUE ? "∞" : to) + (perUnit ? " at " + tierPrice + " each" : " flat"));
                    }
                }
                return null;
            }
            log.warn("configurator: unknown pricingLogicAlgorithm '{}' on price {} — charged flat", spec, price.getId());
        }
        Map<String, Object> uom = price.getUnitOfMeasure();
        if (uom != null && uom.get("units") != null && quantity > 1) {
            int per = Math.max(1, intOf(uom.get("amount"), 1));
            int units = (int) Math.ceil(quantity / (double) per);
            return new PricedLine(value.multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_UP), value, units,
                    units + " × " + value + " per " + per + " " + uom.get("units"));
        }
        return new PricedLine(value, uom != null ? value : null, 1, null);
    }

    private static Map<String, Object> firstAlgorithm(ProductOfferingPriceDto price) {
        List<Map<String, Object>> plas = price.getPricingLogicAlgorithm();
        return plas == null || plas.isEmpty() ? null : plas.get(0);
    }

    /** An unconditioned price always applies; a conditioned one only when EVERY condition matches (exactly, or in range). */
    private boolean priceApplies(ProductOfferingPriceDto price, Map<String, String> picks) {
        List<Map<String, Object>> conditions = price.getProdSpecCharValueUse();
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Map<String, Object> condition : conditions) {
            String picked = picks.get(String.valueOf(condition.get("name")));
            if (picked == null || !valueAllowed(listOf(condition.get("productSpecCharacteristicValue")), picked)) {
                return false;
            }
        }
        return true;
    }

    static boolean inWindow(ProductOfferingPriceDto price, OffsetDateTime now) {
        Map<String, Object> w = price.getValidFor();
        if (w == null) {
            return true;
        }
        OffsetDateTime from = timeOf(w.get("startDateTime"));
        OffsetDateTime to = timeOf(w.get("endDateTime"));
        return (from == null || !now.isBefore(from)) && (to == null || now.isBefore(to));
    }

    /** Does a picked value satisfy the declared values: an exact match, or inside a declared range. */
    static boolean valueAllowed(List<Map<String, Object>> declared, String picked) {
        for (Map<String, Object> v : declared) {
            if (v.get("value") != null && picked.equals(String.valueOf(v.get("value")))) {
                return true;
            }
            if (v.get("valueFrom") != null || v.get("valueTo") != null) {
                Long n = longOrNull(picked);
                if (n == null) {
                    continue;
                }
                long from = v.get("valueFrom") == null ? Long.MIN_VALUE : longOf(v.get("valueFrom"), Long.MIN_VALUE);
                long to = v.get("valueTo") == null ? Long.MAX_VALUE : longOf(v.get("valueTo"), Long.MAX_VALUE);
                String interval = String.valueOf(v.getOrDefault("rangeInterval", "closed"));
                boolean lowOk = interval.equals("open") || interval.equals("closedTop") ? n > from : n >= from;
                boolean highOk = interval.equals("open") || interval.equals("closedBottom") ? n < to : n <= to;
                if (lowOk && highOk) {
                    return true;
                }
            }
        }
        return false;
    }

    static String describeAllowed(List<Map<String, Object>> declared) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> v : declared) {
            if (v.get("value") != null) {
                parts.add(String.valueOf(v.get("value")));
            } else if (v.get("valueFrom") != null || v.get("valueTo") != null) {
                parts.add((v.get("valueFrom") == null ? "…" : v.get("valueFrom")) + "–" + (v.get("valueTo") == null ? "…" : v.get("valueTo")));
            }
        }
        return String.join(", ", parts);
    }

    /* ---------- policy contexts ---------- */

    private Map<String, Object> policyContext(ProductOfferingDto bundle, List<ProductOfferingDto> selectedOfferings, int quantity) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("offeringId", bundle.getId(), "name", bundle.getName(), "quantity", quantity));
        for (ProductOfferingDto o : selectedOfferings) {
            items.add(Map.of("offeringId", o.getId(), "name", o.getName(), "quantity", 1));
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("items", items);
        context.put("itemCount", items.size());
        context.put("maxLineQuantity", quantity);
        context.put("channel", "configurator");
        return context;
    }

    private Map<String, Object> indicativeContext(ProductOfferingDto bundle, List<ProductOfferingDto> selectedOfferings, BigDecimal monthly, int quantity) {
        Map<String, Object> context = policyContext(bundle, selectedOfferings, quantity);
        context.put("subtotal", monthly);
        return context;
    }

    /* ---------- the configuration space, normalized ---------- */

    private static boolean isChoiceGroup(Map<String, Object> member) {
        return member.get("options") instanceof List;
    }

    private Map<String, Object> choiceGroupView(Map<String, Object> group) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", group.get("name"));
        out.put("minSelections", longOf(group.get("numberRelOfferLowerLimit"), 1));
        out.put("maxSelections", longOf(group.get("numberRelOfferUpperLimit"), 1));
        if (group.get("default") != null) {
            out.put("default", group.get("default"));
        }
        List<Map<String, Object>> options = new ArrayList<>();
        for (Map<String, Object> ref : listOf(group.get("options"))) {
            options.add(optionView(String.valueOf(ref.get("id")), ref));
        }
        out.put("option", options);
        out.put("@type", "BundledProductOfferingChoice");
        return out;
    }

    private Map<String, Object> fixedMemberView(Map<String, Object> member) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", member.get("id"));
        out.put("name", member.get("name"));
        Map<String, Object> option = mapOf(member.get("bundledProductOfferingOption"));
        out.put("minCardinality", longOf(option.get("numberRelOfferLowerLimit"), 1));
        out.put("maxCardinality", longOf(option.get("numberRelOfferUpperLimit"), 1));
        out.put("@type", "BundledProductOffering");
        return out;
    }

    private Map<String, Object> optionView(String offeringId, Map<String, Object> ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", offeringId);
        try {
            ProductOfferingDto offering = offerings.findById(offeringId);
            out.put("name", offering.getName());
            List<Map<String, Object>> chars = configurableCharacteristicsOf(offering, stock.availability(offeringId));
            if (!chars.isEmpty()) {
                out.put("configurationCharacteristic", chars);
            }
            List<Map<String, Object>> priceViews = priceViewsOf(offering);
            if (!priceViews.isEmpty()) {
                out.put("price", priceViews);
            }
        } catch (RuntimeException e) {
            out.put("name", ref.get("name"));
            log.warn("configurator: option {} did not resolve: {}", offeringId, e.getMessage());
        }
        out.put("@referredType", "ProductOffering");
        return out;
    }

    /** Absent `configurable` means TRUE — only display-only facts say false. Each value says whether it can be picked (stock). */
    private List<Map<String, Object>> configurableCharacteristicsOf(ProductOfferingDto offering, Map<String, Object> availability) {
        ProductSpecificationDto spec = findSpec(offering);
        if (spec == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ch : listOf(spec.getProductSpecCharacteristic())) {
            if (Boolean.FALSE.equals(ch.get("configurable"))) {
                continue;
            }
            String name = String.valueOf(ch.get("name"));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", name);
            view.put("valueType", ch.getOrDefault("valueType", "string"));
            view.put("configurable", true);
            List<Map<String, Object>> values = new ArrayList<>();
            for (Map<String, Object> v : listOf(ch.get("productSpecCharacteristicValue"))) {
                Map<String, Object> vv = new LinkedHashMap<>(v);
                Boolean selectable = stock.selectable(availability, name, v.get("value"));
                if (selectable != null) {
                    vv.put("isSelectable", selectable);
                }
                values.add(vv);
            }
            view.put("productSpecCharacteristicValue", values);
            out.add(view);
        }
        return out;
    }

    /** name -> declared values, from the bundle's own spec + every PICKED option. */
    private Map<String, List<Map<String, Object>>> allowedValues(ProductOfferingDto bundle, List<ProductOfferingDto> selectedOfferings) {
        Map<String, List<Map<String, Object>>> allowed = new LinkedHashMap<>();
        List<ProductOfferingDto> all = new ArrayList<>();
        all.add(bundle);
        all.addAll(selectedOfferings);
        for (ProductOfferingDto offering : all) {
            for (Map<String, Object> ch : configurableCharacteristicsOf(offering, Map.of())) {
                allowed.computeIfAbsent(String.valueOf(ch.get("name")), k -> new ArrayList<>())
                        .addAll(listOf(ch.get("productSpecCharacteristicValue")));
            }
        }
        return allowed;
    }

    private List<Map<String, Object>> priceViewsOf(ProductOfferingDto offering) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ref : listOf(offering.getProductOfferingPrice())) {
            ProductOfferingPriceDto price = findPrice(String.valueOf(ref.get("id")));
            if (price == null) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", price.getId());
            view.put("name", price.getName());
            view.put("priceType", price.getPriceType());
            view.put("price", price.getPrice());
            if (price.getRecurringChargePeriodType() != null) {
                view.put("recurringChargePeriodType", price.getRecurringChargePeriodType());
            }
            if (price.getUnitOfMeasure() != null) {
                view.put("unitOfMeasure", price.getUnitOfMeasure());
            }
            if (price.getValidFor() != null) {
                view.put("validFor", price.getValidFor());
                view.put("inWindow", inWindow(price, OffsetDateTime.now()));
            }
            if (price.getPricingLogicAlgorithm() != null && !price.getPricingLogicAlgorithm().isEmpty()) {
                view.put("pricingLogicAlgorithm", price.getPricingLogicAlgorithm());
            }
            if (price.getProdSpecCharValueUse() != null && !price.getProdSpecCharValueUse().isEmpty()) {
                view.put("appliesWhen", price.getProdSpecCharValueUse());
            }
            out.add(view);
        }
        return out;
    }

    private List<Map<String, Object>> relationshipViews(ProductOfferingDto offering) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> rel : listOf(offering.getProductOfferingRelationship())) {
            Map<String, Object> view = new LinkedHashMap<>(rel);
            if (view.get("name") == null && view.get("id") != null) {
                try {
                    view.put("name", offerings.findById(String.valueOf(view.get("id"))).getName());
                } catch (RuntimeException e) {
                    // keep the id
                }
            }
            out.add(view);
        }
        return out;
    }

    /** A spec fact `fungible: true` says units are interchangeable (seats, licences): sold in quantity on one line. */
    private boolean isFungible(ProductOfferingDto offering) {
        ProductSpecificationDto spec = findSpec(offering);
        if (spec == null) {
            return false;
        }
        for (Map<String, Object> ch : listOf(spec.getProductSpecCharacteristic())) {
            if ("fungible".equals(ch.get("name"))) {
                List<Map<String, Object>> values = listOf(ch.get("productSpecCharacteristicValue"));
                return !values.isEmpty() && "true".equalsIgnoreCase(String.valueOf(values.get(0).get("value")));
            }
        }
        return false;
    }

    /* ---------- the v5 shape ---------- */

    private Map<String, Object> v5Configuration(ProductOfferingDto offering, List<Map<String, Object>> chars,
            List<Map<String, Object>> priceViews, List<Map<String, Object>> relationships,
            List<Map<String, Object>> fixedMembers, List<Map<String, Object>> choiceGroups) {
        Map<String, Object> pc = new LinkedHashMap<>();
        pc.put("productOffering", ref(offering));
        if (offering.getProductSpecification() != null) {
            pc.put("productSpecification", offering.getProductSpecification());
        }
        pc.put("quantity", 1);
        pc.put("isSelectable", true);
        pc.put("isSelected", true);
        pc.put("isVisible", true);
        List<Map<String, Object>> v5Chars = new ArrayList<>();
        for (Map<String, Object> ch : chars) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", ch.get("name"));
            c.put("name", ch.get("name"));
            c.put("valueType", ch.get("valueType"));
            c.put("isConfigurable", true);
            c.put("minCardinality", 1);
            c.put("maxCardinality", 1);
            List<Map<String, Object>> vals = new ArrayList<>();
            boolean first = true;
            for (Map<String, Object> v : listOf(ch.get("productSpecCharacteristicValue"))) {
                Map<String, Object> cv = new LinkedHashMap<>();
                cv.put("isSelectable", v.getOrDefault("isSelectable", true));
                cv.put("isSelected", Boolean.TRUE.equals(v.get("isDefault")) || (first && v.get("isDefault") == null));
                for (String k : List.of("valueFrom", "valueTo", "rangeInterval", "unitOfMeasure", "regex")) {
                    if (v.get(k) != null) {
                        cv.put(k, v.get(k));
                    }
                }
                if (v.get("value") != null) {
                    cv.put("characteristicValue", Map.of("name", ch.get("name"), "value", v.get("value")));
                }
                cv.put("@type", "ConfigurationCharacteristicValue");
                vals.add(cv);
                first = false;
            }
            c.put("configurationCharacteristicValue", vals);
            c.put("@type", "ConfigurationCharacteristic");
            v5Chars.add(c);
        }
        if (!v5Chars.isEmpty()) {
            pc.put("configurationCharacteristic", v5Chars);
        }
        List<Map<String, Object>> v5Prices = new ArrayList<>();
        for (Map<String, Object> pv : priceViews) {
            Map<String, Object> cp = new LinkedHashMap<>();
            cp.put("name", pv.get("name"));
            cp.put("priceType", pv.get("priceType"));
            cp.put("productOfferingPrice", Map.of("id", pv.get("id"), "@referredType", "ProductOfferingPrice"));
            if (pv.get("unitOfMeasure") != null) {
                cp.put("unitOfMeasure", pv.get("unitOfMeasure"));
            }
            if (pv.get("recurringChargePeriodType") != null) {
                cp.put("recurringChargePeriod", Map.of("amount", 1, "units", pv.get("recurringChargePeriodType")));
            }
            cp.put("price", Map.of("dutyFreeAmount", pv.get("price")));
            if (pv.get("appliesWhen") != null) {
                cp.put("prodSpecCharValueUse", pv.get("appliesWhen"));
            }
            cp.put("@type", "ConfigurationPrice");
            v5Prices.add(cp);
        }
        if (!v5Prices.isEmpty()) {
            pc.put("configurationPrice", v5Prices);
        }
        List<Map<String, Object>> terms = new ArrayList<>();
        for (Map<String, Object> t : listOf(offering.getProductOfferingTerm())) {
            Map<String, Object> ct = new LinkedHashMap<>(t);
            ct.put("isSelectable", true);
            ct.put("isSelected", true);
            ct.put("@type", "ConfigurationTerm");
            terms.add(ct);
        }
        if (!terms.isEmpty()) {
            pc.put("configurationTerm", terms);
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Map<String, Object> rel : relationships) {
            String type = String.valueOf(rel.getOrDefault("relationshipType", "")).toLowerCase();
            if ("requires".equals(type)) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("action", "add");
                a.put("description", "requires " + rel.get("name"));
                a.put("productOffering", Map.of("id", rel.get("id"), "name", rel.getOrDefault("name", rel.get("id")), "@referredType", "ProductOffering"));
                a.put("isSelected", "auto-add".equalsIgnoreCase(String.valueOf(rel.getOrDefault("role", ""))));
                actions.add(a);
            }
        }
        if (!actions.isEmpty()) {
            pc.put("configurationAction", actions);
        }
        List<Map<String, Object>> children = new ArrayList<>();
        for (Map<String, Object> fm : fixedMembers) {
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("productOffering", Map.of("id", fm.get("id"), "name", fm.get("name"), "@referredType", "ProductOffering"));
            child.put("isSelectable", false);
            child.put("isSelected", true);
            child.put("bundledProductOfferingOption", Map.of("numberRelOfferLowerLimit", fm.get("minCardinality"), "numberRelOfferUpperLimit", fm.get("maxCardinality")));
            children.add(child);
        }
        for (Map<String, Object> g : choiceGroups) {
            for (Map<String, Object> opt : listOf(g.get("option"))) {
                Map<String, Object> child = new LinkedHashMap<>();
                child.put("productOffering", Map.of("id", opt.get("id"), "name", opt.getOrDefault("name", opt.get("id")), "@referredType", "ProductOffering"));
                child.put("isSelectable", true);
                child.put("isSelected", opt.get("id").equals(g.get("default")));
                child.put("bundledGroupProductOffering", Map.of("name", g.get("name"), "numberRelOfferLowerLimit", g.get("minSelections"), "numberRelOfferUpperLimit", g.get("maxSelections")));
                children.add(child);
            }
        }
        if (!children.isEmpty()) {
            pc.put("productConfiguration", children);
        }
        pc.put("@type", "ProductConfiguration");
        return pc;
    }

    private static Map<String, Object> v5Price(ProductOfferingPriceDto price, PricedLine pl) {
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("name", price.getName());
        cp.put("priceType", price.getPriceType());
        cp.put("productOfferingPrice", Map.of("id", price.getId(), "@referredType", "ProductOfferingPrice"));
        if (price.getUnitOfMeasure() != null) {
            cp.put("unitOfMeasure", price.getUnitOfMeasure());
        }
        String unit = price.getPrice() == null || price.getPrice().get("unit") == null ? "EUR" : String.valueOf(price.getPrice().get("unit"));
        cp.put("price", Map.of("dutyFreeAmount", Map.of("unit", unit, "value", pl.amount)));
        if (pl.unitPrice != null) {
            cp.put("quantity", pl.units);
        }
        cp.put("@type", "ConfigurationPrice");
        return cp;
    }

    /* ---------- small helpers ---------- */

    private static Map<String, Object> ref(ProductOfferingDto o) {
        return Map.of("id", o.getId(), "name", o.getName(), "@referredType", "ProductOffering");
    }

    private ProductSpecificationDto findSpec(ProductOfferingDto offering) {
        Map<String, Object> ref = mapOf(offering.getProductSpecification());
        if (ref.get("id") == null) {
            return null;
        }
        try {
            return specs.findById(String.valueOf(ref.get("id")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ProductOfferingPriceDto findPrice(String id) {
        try {
            return prices.findById(id);
        } catch (RuntimeException e) {
            log.warn("configurator: price {} did not resolve: {}", id, e.getMessage());
            return null;
        }
    }

    private static String offeringIdOf(Map<String, Object> request) {
        Map<String, Object> config = mapOf(request.getOrDefault("productConfiguration", request));
        Map<String, Object> ref = mapOf(config.get("productOffering"));
        if (ref.get("id") == null) {
            throw new BadRequestException("productOffering.id is required");
        }
        return String.valueOf(ref.get("id"));
    }

    private static BigDecimal amountOf(ProductOfferingPriceDto price) {
        Object value = price.getPrice() == null ? null : price.getPrice().get("value");
        return decimalOf(value, BigDecimal.ZERO);
    }

    private static BigDecimal decimalOf(Object v, BigDecimal dflt) {
        try {
            return v == null ? dflt : new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static OffsetDateTime timeOf(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    static long longOf(Object v, long dflt) {
        try {
            return v == null ? dflt : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Long longOrNull(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(v.trim());
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static int intOf(Object v, int dflt) {
        try {
            return v == null ? dflt : (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapOf(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOf(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }
}
