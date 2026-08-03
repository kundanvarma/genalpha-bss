package com.bss.catalog.service;

import com.bss.catalog.client.PolicyClient;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TMF760, the house way: the configurator the storefront already implies,
 * promoted to a server-side capability any channel can call. The catalog
 * owns every input — choice groups on the bundle, configurable
 * characteristics on the specs, prices conditioned on picked values — so
 * the two task resources compute here, statelessly, from the same rows
 * the shop window reads.
 *
 * The check is deliberately STRICTER than any single channel today: the
 * storefront only enforces the lower pick bound (the upper is UI physics),
 * ordering enforces both but only at submit time, and nobody validates
 * characteristic VALUES anywhere. Here all three run, and rejections speak
 * ordering's error language so a configure-time "no" reads exactly like
 * the order-time 400 it prevents.
 */
@Service
public class ConfiguratorService {

    private static final Logger log = LoggerFactory.getLogger(ConfiguratorService.class);

    private final ProductOfferingService offerings;
    private final ProductOfferingPriceService prices;
    private final ProductSpecificationService specs;
    private final PolicyClient policy;

    public ConfiguratorService(ProductOfferingService offerings, ProductOfferingPriceService prices,
            ProductSpecificationService specs, PolicyClient policy) {
        this.offerings = offerings;
        this.prices = prices;
        this.specs = specs;
        this.policy = policy;
    }

    /* =====================================================================
     * queryProductConfiguration: the configuration SPACE as data — the
     * storefront's Offering page, answered by the server.
     * ===================================================================== */

    @Transactional(readOnly = true)
    public Map<String, Object> query(Map<String, Object> request) {
        String offeringId = offeringIdOf(request);
        ProductOfferingDto offering = offerings.findById(offeringId);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productOffering", Map.of("id", offering.getId(), "name", offering.getName(),
                "@referredType", "ProductOffering"));
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

        List<Map<String, Object>> ownChars = configurableCharacteristicsOf(offering);
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
        item.put("@type", "ComputedProductConfiguration");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", offeringId);
        out.put("state", "done");
        out.put("instantSync", true);
        out.put("computedProductConfigurationItem", List.of(item));
        out.put("@type", "QueryProductConfiguration");
        return out;
    }

    /* =====================================================================
     * checkProductConfiguration: is THIS pick set orderable, and what
     * does it cost? Both bounds, allowed values, policy, then the price.
     * ===================================================================== */

    @Transactional(readOnly = true)
    public Map<String, Object> check(Map<String, Object> request) {
        List<Map<String, Object>> items = listOf(request.get("checkProductConfigurationItem"));
        if (items.isEmpty()) {
            throw new BadRequestException("checkProductConfigurationItem is required");
        }
        List<Map<String, Object>> outItems = new ArrayList<>();
        boolean allApproved = true;
        int seq = 0;
        for (Map<String, Object> item : items) {
            seq++;
            Map<String, Object> config = mapOf(item.get("productConfiguration"));
            Map<String, Object> outItem = checkOne(config);
            outItem.put("id", item.get("id") == null ? String.valueOf(seq) : item.get("id"));
            allApproved = allApproved && "approved".equals(outItem.get("state"));
            outItems.add(outItem);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", "done");
        out.put("instantSync", true);
        out.put("result", allApproved ? "approved" : "rejected");
        out.put("checkProductConfigurationItem", outItems);
        out.put("@type", "CheckProductConfiguration");
        return out;
    }

    private Map<String, Object> checkOne(Map<String, Object> config) {
        String offeringId = offeringIdOf(config);
        ProductOfferingDto bundle = offerings.findById(offeringId);
        List<String> selected = listOf(config.get("selectedOption")).stream()
                .map(o -> String.valueOf(o.get("id"))).toList();
        Map<String, String> picks = new LinkedHashMap<>();
        for (Map<String, Object> c : listOf(config.get("configurationCharacteristic"))) {
            if (c.get("name") != null) {
                picks.put(String.valueOf(c.get("name")), String.valueOf(c.get("value")));
            }
        }

        List<String> messages = new ArrayList<>();

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
                messages.add("selected offering '" + id + "' is not part of bundle '"
                        + bundle.getName() + "'");
            }
        }

        // 2. cardinality, BOTH bounds — ordering's wording, configure-time
        for (Map<String, Object> group : choiceGroups) {
            long lower = longOf(group.get("numberRelOfferLowerLimit"), 1);
            long upper = longOf(group.get("numberRelOfferUpperLimit"), 1);
            long chosen = listOf(group.get("options")).stream()
                    .map(o -> String.valueOf(o.get("id"))).filter(selected::contains).count();
            if (chosen < lower || chosen > upper) {
                String need = lower == upper ? "exactly " + lower
                        : "between " + lower + " and " + upper;
                messages.add("bundle '" + bundle.getName() + "': '" + group.get("name")
                        + "' requires " + need + " selection(s), but " + chosen + " were made");
            }
        }

        // 3. characteristic values against the specs the picks brought in
        List<ProductOfferingDto> selectedOfferings = new ArrayList<>();
        for (String id : selected) {
            if (memberIds.contains(id)) {
                selectedOfferings.add(offerings.findById(id));
            }
        }
        Map<String, Set<String>> allowed = allowedValues(bundle, selectedOfferings);
        for (Map.Entry<String, String> pick : picks.entrySet()) {
            Set<String> values = allowed.get(pick.getKey());
            if (values == null) {
                messages.add("characteristic '" + pick.getKey()
                        + "' is not configurable on this configuration");
            } else if (!values.contains(pick.getValue())) {
                messages.add("characteristic '" + pick.getKey() + "' value '" + pick.getValue()
                        + "' is not an allowed value (allowed: " + String.join(", ", values) + ")");
            }
        }

        // 4. the same block rules that will guard the order at submit time
        PolicyClient.Verdict verdict = PolicyClient.Verdict.allow();
        if (messages.isEmpty()) {
            verdict = policy.evaluate(policyContext(bundle, selectedOfferings));
            if (!verdict.allowed()) {
                messages.add(verdict.message());
            }
        }

        Map<String, Object> outItem = new LinkedHashMap<>();
        if (messages.isEmpty()) {
            outItem.put("state", "approved");
            outItem.put("configurationPrice", priceConfiguration(bundle, selectedOfferings, picks));
        } else {
            outItem.put("state", "rejected");
            outItem.put("message", messages);
            if (!verdict.allowed() && verdict.ruleName() != null) {
                outItem.put("ruleName", verdict.ruleName());
            }
        }
        outItem.put("productConfiguration", Map.of(
                "productOffering", Map.of("id", bundle.getId(), "name", bundle.getName()),
                "@type", "ProductConfiguration"));
        outItem.put("@type", "CheckProductConfigurationItem");
        return outItem;
    }

    /* =====================================================================
     * pricing: money.js priceApplies/pricesOf, ported verbatim — an
     * unconditioned price always applies; a conditioned one only when
     * EVERY condition matches the picked value by name, exactly.
     * ===================================================================== */

    private Map<String, Object> priceConfiguration(ProductOfferingDto bundle,
            List<ProductOfferingDto> selectedOfferings, Map<String, String> picks) {
        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal monthly = BigDecimal.ZERO;
        BigDecimal oneTime = BigDecimal.ZERO;
        List<ProductOfferingDto> all = new ArrayList<>();
        all.add(bundle);
        all.addAll(selectedOfferings);
        for (ProductOfferingDto offering : all) {
            for (Map<String, Object> ref : listOf(offering.getProductOfferingPrice())) {
                ProductOfferingPriceDto price = findPrice(String.valueOf(ref.get("id")));
                if (price == null || !priceApplies(price, picks)) {
                    continue;
                }
                BigDecimal value = amountOf(price);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("offering", Map.of("id", offering.getId(), "name", offering.getName()));
                line.put("name", price.getName());
                line.put("priceType", price.getPriceType());
                line.put("price", price.getPrice());
                if (price.getProdSpecCharValueUse() != null && !price.getProdSpecCharValueUse().isEmpty()) {
                    line.put("appliesWhen", price.getProdSpecCharValueUse());
                }
                lines.add(line);
                if ("recurring".equals(price.getPriceType())) {
                    monthly = monthly.add(value);
                } else if ("oneTime".equals(price.getPriceType())) {
                    oneTime = oneTime.add(value);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("monthlyTotal", Map.of("unit", "EUR", "value", monthly));
        out.put("oneTimeTotal", Map.of("unit", "EUR", "value", oneTime));
        out.put("priceLine", lines);
        // the public deal engine's view of this basket — advisory, never binding
        Map<String, Object> indicative = policy.indicativePrice(indicativeContext(
                bundle, selectedOfferings, monthly));
        if (indicative != null) {
            out.put("indicative", indicative);
        }
        out.put("@type", "ConfigurationPrice");
        return out;
    }

    /** money.js priceApplies(): the exact-match semantics, server-side. */
    private boolean priceApplies(ProductOfferingPriceDto price, Map<String, String> picks) {
        List<Map<String, Object>> conditions = price.getProdSpecCharValueUse();
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (Map<String, Object> condition : conditions) {
            String name = String.valueOf(condition.get("name"));
            List<String> allowed = listOf(condition.get("productSpecCharacteristicValue")).stream()
                    .map(v -> String.valueOf(v.get("value"))).toList();
            String picked = picks.get(name);
            if (picked == null || !allowed.contains(picked)) {
                return false;
            }
        }
        return true;
    }

    /* ---------- policy contexts ---------- */

    private Map<String, Object> policyContext(ProductOfferingDto bundle,
            List<ProductOfferingDto> selectedOfferings) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("offeringId", bundle.getId(), "name", bundle.getName(), "quantity", 1));
        for (ProductOfferingDto o : selectedOfferings) {
            items.add(Map.of("offeringId", o.getId(), "name", o.getName(), "quantity", 1));
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("items", items);
        context.put("itemCount", items.size());
        context.put("maxLineQuantity", 1);
        context.put("channel", "configurator");
        return context;
    }

    private Map<String, Object> indicativeContext(ProductOfferingDto bundle,
            List<ProductOfferingDto> selectedOfferings, BigDecimal monthly) {
        Map<String, Object> context = policyContext(bundle, selectedOfferings);
        context.put("subtotal", monthly);
        return context;
    }

    /* ---------- the configuration space, normalized ---------- */

    /** Consumers detect a choice group by its options array, not by @type. */
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

    /** An option, resolved: its pickers and its prices, conditions visible. */
    private Map<String, Object> optionView(String offeringId, Map<String, Object> ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", offeringId);
        try {
            ProductOfferingDto offering = offerings.findById(offeringId);
            out.put("name", offering.getName());
            List<Map<String, Object>> chars = configurableCharacteristicsOf(offering);
            if (!chars.isEmpty()) {
                out.put("configurationCharacteristic", chars);
            }
            List<Map<String, Object>> priceViews = priceViewsOf(offering);
            if (!priceViews.isEmpty()) {
                out.put("price", priceViews);
            }
        } catch (RuntimeException e) {
            // a dangling ref: keep the seed's name, describe nothing further
            out.put("name", ref.get("name"));
            log.warn("configurator: option {} did not resolve: {}", offeringId, e.getMessage());
        }
        out.put("@referredType", "ProductOffering");
        return out;
    }

    /** Absent `configurable` means TRUE — only display-only facts say false. */
    private List<Map<String, Object>> configurableCharacteristicsOf(ProductOfferingDto offering) {
        ProductSpecificationDto spec = findSpec(offering);
        if (spec == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ch : listOf(spec.getProductSpecCharacteristic())) {
            if (Boolean.FALSE.equals(ch.get("configurable"))) {
                continue;
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", ch.get("name"));
            view.put("valueType", ch.getOrDefault("valueType", "string"));
            view.put("configurable", true);
            view.put("productSpecCharacteristicValue",
                    listOf(ch.get("productSpecCharacteristicValue")));
            out.add(view);
        }
        return out;
    }

    /** name -> allowed values, from the bundle's own spec + every PICKED option. */
    private Map<String, Set<String>> allowedValues(ProductOfferingDto bundle,
            List<ProductOfferingDto> selectedOfferings) {
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        List<ProductOfferingDto> all = new ArrayList<>();
        all.add(bundle);
        all.addAll(selectedOfferings);
        for (ProductOfferingDto offering : all) {
            for (Map<String, Object> ch : configurableCharacteristicsOf(offering)) {
                Set<String> values = allowed.computeIfAbsent(
                        String.valueOf(ch.get("name")), k -> new LinkedHashSet<>());
                for (Map<String, Object> v : listOf(ch.get("productSpecCharacteristicValue"))) {
                    values.add(String.valueOf(v.get("value")));
                }
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
            if (price.getProdSpecCharValueUse() != null && !price.getProdSpecCharValueUse().isEmpty()) {
                view.put("appliesWhen", price.getProdSpecCharValueUse());
            }
            out.add(view);
        }
        return out;
    }

    /* ---------- small helpers ---------- */

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
        try {
            return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static long longOf(Object v, long dflt) {
        try {
            return v == null ? dflt : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
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
