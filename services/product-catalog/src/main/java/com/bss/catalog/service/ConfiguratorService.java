package com.bss.catalog.service;

import com.bss.catalog.client.PolicyClient;
import com.bss.catalog.client.StockReader;
import com.bss.catalog.dto.Availability;
import com.bss.catalog.dto.CheckProductConfiguration;
import com.bss.catalog.dto.ComputedProductConfigurationItem;
import com.bss.catalog.dto.ComputedProductConfigurationItem.ChoiceGroup;
import com.bss.catalog.dto.ComputedProductConfigurationItem.ChoiceOption;
import com.bss.catalog.dto.ComputedProductConfigurationItem.FixedMember;
import com.bss.catalog.dto.ConfigurableCharacteristic;
import com.bss.catalog.dto.ConfigurationAction;
import com.bss.catalog.dto.ConfigurationPrice;
import com.bss.catalog.dto.ConfigurationPriceSummary;
import com.bss.catalog.dto.ConfigurationPriceSummary.EarlyTermination;
import com.bss.catalog.dto.ConfigurationPriceSummary.PriceLine;
import com.bss.catalog.dto.EntityRef;
import com.bss.catalog.dto.IndicativePrice;
import com.bss.catalog.dto.Money;
import com.bss.catalog.dto.NameValue;
import com.bss.catalog.dto.OrderReadyConfiguration;
import com.bss.catalog.dto.PriceView;
import com.bss.catalog.dto.ProductConfiguration;
import com.bss.catalog.dto.ProductConfigurationRequest;
import com.bss.catalog.dto.ProductOfferingDto;
import com.bss.catalog.dto.ProductOfferingPriceDto;
import com.bss.catalog.dto.ProductSpecificationDto;
import com.bss.catalog.dto.Quantity;
import com.bss.catalog.dto.QueryProductConfiguration;
import com.bss.catalog.dto.TimePeriod;
import com.bss.catalog.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Objects;
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
 * `configurationPrice` totals and the flat `message[]` earlier clients read.
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
    public QueryProductConfiguration query(ProductConfigurationRequest.Query request) {
        String offeringId = offeringIdOf(request.configuration());
        ProductOfferingDto offering = offerings.findById(offeringId);
        Availability availability = stock.availability(offeringId);

        // ---- the house view (what the first clients read)
        List<FixedMember> fixedMembers = new ArrayList<>();
        List<ChoiceGroup> choiceGroups = new ArrayList<>();
        for (Map<String, Object> member : listOf(offering.getBundledProductOffering())) {
            if (isChoiceGroup(member)) {
                choiceGroups.add(choiceGroupView(member));
            } else {
                fixedMembers.add(fixedMemberView(member));
            }
        }
        List<ConfigurableCharacteristic> ownChars = configurableCharacteristicsOf(offering, availability);
        List<PriceView> ownPrices = priceViewsOf(offering);
        List<Map<String, Object>> relationships = relationshipViews(offering);
        ComputedProductConfigurationItem item = new ComputedProductConfigurationItem(ref(offering),
                Boolean.TRUE.equals(offering.getIsBundle()), orNull(fixedMembers), orNull(choiceGroups), orNull(ownChars),
                orNull(ownPrices), orNull(offering.getProductOfferingTerm()), orNull(relationships), isFungible(offering),
                availability.managed() ? availability : null, "ComputedProductConfiguration");

        // ---- the v5 item: the same facts in the standard's shape
        QueryProductConfiguration.Item v5 = new QueryProductConfiguration.Item("1", "accepted",
                v5Configuration(offering, ownChars, ownPrices, relationships, fixedMembers, choiceGroups), "QueryProductConfigurationItem");

        return new QueryProductConfiguration(offeringId, "done", true, List.of(v5), List.of(item), "QueryProductConfiguration");
    }

    /* =====================================================================
     * checkProductConfiguration: is THIS pick set orderable, what does it
     * cost, and what must ride along.
     * ===================================================================== */

    @Transactional(readOnly = true)
    public CheckProductConfiguration check(ProductConfigurationRequest.Check request) {
        List<ProductConfigurationRequest.Check.Item> items = request.checkProductConfigurationItem() == null ? List.of()
                : request.checkProductConfigurationItem().stream().filter(Objects::nonNull).toList();
        if (items.isEmpty()) {
            throw new BadRequestException("checkProductConfigurationItem is required");
        }
        List<CheckProductConfiguration.Item> outItems = new ArrayList<>();
        boolean allAccepted = true;
        int seq = 0;
        for (ProductConfigurationRequest.Check.Item item : items) {
            seq++;
            CheckProductConfiguration.Item outItem = checkOne(item.configuration(), item.id() == null ? String.valueOf(seq) : item.id());
            allAccepted = allAccepted && outItem.accepted();
            outItems.add(outItem);
        }
        return new CheckProductConfiguration("done", true, allAccepted ? "accepted" : "rejected", outItems, "CheckProductConfiguration");
    }

    private CheckProductConfiguration.Item checkOne(ProductConfigurationRequest config, String itemId) {
        String offeringId = offeringIdOf(config);
        ProductOfferingDto bundle = offerings.findById(offeringId);
        List<String> selected = new ArrayList<>(config.selectedOptionOrEmpty().stream().filter(Objects::nonNull)
                .map(o -> String.valueOf(o.id())).toList());
        Map<String, String> picks = picksOf(config.configurationCharacteristic());
        int quantity = config.quantityOr(1);
        // an INSTALLED product is priced as configured: today's stock and today's relationships do not change
        // what a subscriber already holds — billing asks with priceOnly
        boolean priceOnly = config.isPriceOnly();
        List<String> messages = new ArrayList<>();
        List<CheckProductConfiguration.StateReason> reasons = new ArrayList<>();
        List<ConfigurationAction> actions = new ArrayList<>();

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
                actions.add(new ConfigurationAction("add", "'" + bundle.getName() + "' requires '" + relName + "'",
                        EntityRef.of(relId, relName, "ProductOffering"), "auto-add".equals(role), role));
                if ("block".equals(role)) {
                    reject(messages, reasons, "requires", "'" + bundle.getName() + "' requires '" + relName + "' — add it first");
                }
            }
        }
        // 6. availability: a configured variant that is stock-managed must be in stock
        Availability availability = priceOnly ? Availability.NONE : stock.availability(offeringId);
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

        List<OrderReadyConfiguration.SelectedOption> options = new ArrayList<>();
        for (ProductOfferingDto offering : selectedOfferings) {
            options.add(new OrderReadyConfiguration.SelectedOption(offering.getId(), offering.getName(), orNull(picksOwnedBy(offering, picks))));
        }
        List<NameValue> ownPicks = orNull(picksOwnedBy(bundle, picks));
        if (messages.isEmpty()) {
            ConfigurationPriceSummary priced = priceConfiguration(bundle, selectedOfferings, picks, quantity);
            OrderReadyConfiguration configuration = new OrderReadyConfiguration(ref(bundle), quantity, options, ownPicks,
                    priced.configurationPrice(), orNull(actions), "ProductConfiguration");
            return new CheckProductConfiguration.Item(itemId, "accepted", priced, null, null, null, configuration, "CheckProductConfigurationItem");
        }
        OrderReadyConfiguration configuration = new OrderReadyConfiguration(ref(bundle), quantity, options, ownPicks,
                null, orNull(actions), "ProductConfiguration");
        String ruleName = !verdict.allowed() && verdict.ruleName() != null ? verdict.ruleName() : null;
        return new CheckProductConfiguration.Item(itemId, "rejected", null, messages, reasons, ruleName, configuration, "CheckProductConfigurationItem");
    }

    private static void reject(List<String> messages, List<CheckProductConfiguration.StateReason> reasons, String code, String label) {
        messages.add(label);
        reasons.add(new CheckProductConfiguration.StateReason(code, label));
    }

    /** Picks in the house shape ({name, value}), the v5 shape (selected characteristic values) or the ontology's flat string. */
    private static Map<String, String> picksOf(JsonNode node) {
        Map<String, String> picks = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return picks;
        }
        if (node.isTextual()) {
            // the ontology's action inputs are strings: "screens=5+, extraProfiles=6"
            for (String pair : node.asText().split("[,;]")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    picks.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            return picks;
        }
        if (!node.isArray()) {
            return picks;
        }
        for (JsonNode c : node) {
            if (!c.isObject() || isAbsent(c.get("name"))) {
                continue;
            }
            String name = textOf(c.get("name"));
            if (!isAbsent(c.get("value"))) {
                picks.put(name, textOf(c.get("value")));
                continue;
            }
            JsonNode values = c.get("configurationCharacteristicValue");
            if (values == null || !values.isArray()) {
                continue;
            }
            for (JsonNode v : values) {
                JsonNode sel = v.get("isSelected");
                if ((sel != null && sel.isBoolean() && sel.booleanValue()) || values.size() == 1) {
                    JsonNode val = v.path("characteristicValue").get("value");
                    if (!isAbsent(val)) {
                        picks.put(name, textOf(val));
                    }
                }
            }
        }
        return picks;
    }

    private static boolean isAbsent(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode();
    }

    private static String textOf(JsonNode n) {
        return n.isValueNode() ? n.asText() : n.toString();
    }

    /* ---------- the order-ready echo ---------- */

    /** The picks whose name belongs to this offering's own spec and whose value it allows. */
    private List<NameValue> picksOwnedBy(ProductOfferingDto offering, Map<String, String> picks) {
        List<NameValue> owned = new ArrayList<>();
        for (ConfigurableCharacteristic ch : configurableCharacteristicsOf(offering, Availability.NONE)) {
            String picked = picks.get(ch.name());
            if (picked != null && valueAllowed(ch.productSpecCharacteristicValue(), picked)) {
                owned.add(new NameValue(ch.name(), picked));
            }
        }
        return owned;
    }

    /* =====================================================================
     * pricing: every price that applies — unconditioned, conditioned on
     * picks (exact or in range), within its window — per unit times the
     * quantity, named algorithms evaluated. Totals in the tenant's money.
     * ===================================================================== */

    private ConfigurationPriceSummary priceConfiguration(ProductOfferingDto bundle,
            List<ProductOfferingDto> selectedOfferings, Map<String, String> picks, int quantity) {
        List<PriceLine> lines = new ArrayList<>();
        List<ConfigurationPrice> v5Lines = new ArrayList<>();
        BigDecimal monthly = BigDecimal.ZERO;
        BigDecimal oneTime = BigDecimal.ZERO;
        String currency = null;
        OffsetDateTime now = OffsetDateTime.now();
        List<ProductOfferingDto> all = new ArrayList<>();
        all.add(bundle);
        all.addAll(selectedOfferings);
        List<EarlyTermination> terminations = new ArrayList<>();
        for (ProductOfferingDto offering : all) {
            int qty = offering == bundle ? quantity : 1;
            for (Map<String, Object> ref : listOf(offering.getProductOfferingPrice())) {
                ProductOfferingPriceDto price = findPrice(String.valueOf(ref.get("id")));
                if (price == null || !priceApplies(price, picks) || !inWindow(price, now)) {
                    continue;
                }
                if ("penalty".equals(price.getPriceType())) {
                    // never a charge on the configuration: what leaving early would cost, declining over the term
                    Quantity term = price.getUnitOfMeasure();
                    String says = "leaving early costs at most " + amountOf(price) + " " + (price.getPrice() == null ? "" : price.getPrice().unit())
                            + (term != null ? ", falling by a twelfth each month of the " + term.amount() + "-" + term.units() + " term" : "");
                    terminations.add(new EarlyTermination(price.getName(), price.getPrice(), term, says));
                    continue;
                }
                PricedLine pl = evaluate(price, picks, qty);
                if (pl == null) {
                    continue;
                }
                if (currency == null && price.getPrice() != null && price.getPrice().unit() != null) {
                    currency = price.getPrice().unit();
                }
                lines.add(new PriceLine(EntityRef.of(offering.getId(), offering.getName()), price.getName(), price.getPriceType(),
                        price.getPrice(), pl.unitPrice, pl.unitPrice != null ? pl.units : null, pl.amount, pl.how,
                        orNull(price.getProdSpecCharValueUse())));
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
        IndicativePrice indicative = policy.indicativePrice(indicativeContext(bundle, selectedOfferings, monthly, quantity));
        return new ConfigurationPriceSummary(new Money(currency, monthly), new Money(currency, oneTime), lines, v5Lines,
                orNull(terminations), indicative, "ConfigurationPrice");
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
        Quantity uom = price.getUnitOfMeasure();
        if (uom != null && uom.units() != null && quantity > 1) {
            int per = Math.max(1, intOf(uom.amount(), 1));
            int units = (int) Math.ceil(quantity / (double) per);
            return new PricedLine(value.multiply(BigDecimal.valueOf(units)).setScale(2, RoundingMode.HALF_UP), value, units,
                    units + " × " + value + " per " + per + " " + uom.units());
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
        TimePeriod w = price.getValidFor();
        return w == null || w.contains(now);
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

    private PolicyClient.OrderContext policyContext(ProductOfferingDto bundle, List<ProductOfferingDto> selectedOfferings, int quantity) {
        List<PolicyClient.OrderItem> items = new ArrayList<>();
        items.add(new PolicyClient.OrderItem(bundle.getId(), bundle.getName(), quantity));
        for (ProductOfferingDto o : selectedOfferings) {
            items.add(new PolicyClient.OrderItem(o.getId(), o.getName(), 1));
        }
        return new PolicyClient.OrderContext(items, items.size(), quantity, "configurator", null);
    }

    private PolicyClient.OrderContext indicativeContext(ProductOfferingDto bundle, List<ProductOfferingDto> selectedOfferings,
            BigDecimal monthly, int quantity) {
        return policyContext(bundle, selectedOfferings, quantity).withSubtotal(monthly);
    }

    /* ---------- the configuration space, normalized ---------- */

    private static boolean isChoiceGroup(Map<String, Object> member) {
        return member.get("options") instanceof List;
    }

    private ChoiceGroup choiceGroupView(Map<String, Object> group) {
        List<ChoiceOption> options = new ArrayList<>();
        for (Map<String, Object> ref : listOf(group.get("options"))) {
            options.add(optionView(String.valueOf(ref.get("id")), ref));
        }
        return new ChoiceGroup(str(group.get("name")), longOf(group.get("numberRelOfferLowerLimit"), 1),
                longOf(group.get("numberRelOfferUpperLimit"), 1), str(group.get("default")), options, "BundledProductOfferingChoice");
    }

    private FixedMember fixedMemberView(Map<String, Object> member) {
        Map<String, Object> option = mapOf(member.get("bundledProductOfferingOption"));
        return new FixedMember(str(member.get("id")), str(member.get("name")), longOf(option.get("numberRelOfferLowerLimit"), 1),
                longOf(option.get("numberRelOfferUpperLimit"), 1), "BundledProductOffering");
    }

    private ChoiceOption optionView(String offeringId, Map<String, Object> ref) {
        try {
            ProductOfferingDto offering = offerings.findById(offeringId);
            List<ConfigurableCharacteristic> chars = configurableCharacteristicsOf(offering, stock.availability(offeringId));
            return new ChoiceOption(offeringId, offering.getName(), orNull(chars), orNull(priceViewsOf(offering)), "ProductOffering");
        } catch (RuntimeException e) {
            log.warn("configurator: option {} did not resolve: {}", offeringId, e.getMessage());
            return new ChoiceOption(offeringId, str(ref.get("name")), null, null, "ProductOffering");
        }
    }

    /** Absent `configurable` means TRUE — only display-only facts say false. Each value says whether it can be picked (stock). */
    private List<ConfigurableCharacteristic> configurableCharacteristicsOf(ProductOfferingDto offering, Availability availability) {
        ProductSpecificationDto spec = findSpec(offering);
        if (spec == null) {
            return List.of();
        }
        List<ConfigurableCharacteristic> out = new ArrayList<>();
        for (Map<String, Object> ch : listOf(spec.getProductSpecCharacteristic())) {
            if (Boolean.FALSE.equals(ch.get("configurable"))) {
                continue;
            }
            String name = String.valueOf(ch.get("name"));
            List<Map<String, Object>> values = new ArrayList<>();
            for (Map<String, Object> v : listOf(ch.get("productSpecCharacteristicValue"))) {
                Map<String, Object> vv = new LinkedHashMap<>(v);
                Boolean selectable = stock.selectable(availability, name, v.get("value"));
                if (selectable != null) {
                    vv.put("isSelectable", selectable);
                }
                values.add(vv);
            }
            out.add(new ConfigurableCharacteristic(name, str(ch.getOrDefault("valueType", "string")), true, values));
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
            for (ConfigurableCharacteristic ch : configurableCharacteristicsOf(offering, Availability.NONE)) {
                allowed.computeIfAbsent(ch.name(), k -> new ArrayList<>()).addAll(ch.productSpecCharacteristicValue());
            }
        }
        return allowed;
    }

    private List<PriceView> priceViewsOf(ProductOfferingDto offering) {
        List<PriceView> out = new ArrayList<>();
        for (Map<String, Object> ref : listOf(offering.getProductOfferingPrice())) {
            ProductOfferingPriceDto price = findPrice(String.valueOf(ref.get("id")));
            if (price == null) {
                continue;
            }
            out.add(new PriceView(price.getId(), price.getName(), price.getPriceType(), price.getPrice(),
                    price.getRecurringChargePeriodType(), price.getUnitOfMeasure(), price.getValidFor(),
                    price.getValidFor() != null ? inWindow(price, OffsetDateTime.now()) : null,
                    orNull(price.getPricingLogicAlgorithm()), orNull(price.getProdSpecCharValueUse())));
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

    private ProductConfiguration v5Configuration(ProductOfferingDto offering, List<ConfigurableCharacteristic> chars,
            List<PriceView> priceViews, List<Map<String, Object>> relationships,
            List<FixedMember> fixedMembers, List<ChoiceGroup> choiceGroups) {
        List<ProductConfiguration.Characteristic> v5Chars = new ArrayList<>();
        for (ConfigurableCharacteristic ch : chars) {
            List<ProductConfiguration.CharacteristicValue> vals = new ArrayList<>();
            boolean first = true;
            for (Map<String, Object> v : ch.productSpecCharacteristicValue()) {
                vals.add(new ProductConfiguration.CharacteristicValue(v.getOrDefault("isSelectable", true),
                        Boolean.TRUE.equals(v.get("isDefault")) || (first && v.get("isDefault") == null),
                        v.get("valueFrom"), v.get("valueTo"), v.get("rangeInterval"), v.get("unitOfMeasure"), v.get("regex"),
                        v.get("value") != null ? new NameValue(ch.name(), v.get("value")) : null, "ConfigurationCharacteristicValue"));
                first = false;
            }
            v5Chars.add(new ProductConfiguration.Characteristic(ch.name(), ch.name(), ch.valueType(), true, 1, 1, vals, "ConfigurationCharacteristic"));
        }
        List<ConfigurationPrice> v5Prices = new ArrayList<>();
        for (PriceView pv : priceViews) {
            v5Prices.add(new ConfigurationPrice(pv.name(), pv.priceType(), EntityRef.to(pv.id(), "ProductOfferingPrice"), pv.unitOfMeasure(),
                    pv.recurringChargePeriodType() != null ? new Quantity(1, pv.recurringChargePeriodType()) : null,
                    new ConfigurationPrice.Amount(pv.price()), null, pv.appliesWhen(), "ConfigurationPrice"));
        }
        List<Map<String, Object>> terms = new ArrayList<>();
        for (Map<String, Object> t : listOf(offering.getProductOfferingTerm())) {
            Map<String, Object> ct = new LinkedHashMap<>(t);
            ct.put("isSelectable", true);
            ct.put("isSelected", true);
            ct.put("@type", "ConfigurationTerm");
            terms.add(ct);
        }
        List<ConfigurationAction> actions = new ArrayList<>();
        for (Map<String, Object> rel : relationships) {
            String type = String.valueOf(rel.getOrDefault("relationshipType", "")).toLowerCase();
            if ("requires".equals(type)) {
                actions.add(new ConfigurationAction("add", "requires " + rel.get("name"),
                        EntityRef.of(str(rel.get("id")), str(rel.getOrDefault("name", rel.get("id"))), "ProductOffering"),
                        "auto-add".equalsIgnoreCase(String.valueOf(rel.getOrDefault("role", ""))), null));
            }
        }
        List<ProductConfiguration.Child> children = new ArrayList<>();
        for (FixedMember fm : fixedMembers) {
            children.add(new ProductConfiguration.Child(EntityRef.of(fm.id(), fm.name(), "ProductOffering"), false, true,
                    new ProductConfiguration.Cardinality(fm.minCardinality(), fm.maxCardinality()), null));
        }
        for (ChoiceGroup g : choiceGroups) {
            for (ChoiceOption opt : g.option()) {
                children.add(new ProductConfiguration.Child(EntityRef.of(opt.id(), opt.name() != null ? opt.name() : opt.id(), "ProductOffering"),
                        true, opt.id().equals(g.defaultOption()), null,
                        new ProductConfiguration.GroupCardinality(g.name(), g.minSelections(), g.maxSelections())));
            }
        }
        return new ProductConfiguration(ref(offering), offering.getProductSpecification(), 1, true, true, true,
                orNull(v5Chars), orNull(v5Prices), orNull(terms), orNull(actions), orNull(children), "ProductConfiguration");
    }

    private static ConfigurationPrice v5Price(ProductOfferingPriceDto price, PricedLine pl) {
        String unit = price.getPrice() == null || price.getPrice().unit() == null ? "EUR" : price.getPrice().unit();
        return new ConfigurationPrice(price.getName(), price.getPriceType(), EntityRef.to(price.getId(), "ProductOfferingPrice"),
                price.getUnitOfMeasure(), null, new ConfigurationPrice.Amount(new Money(unit, pl.amount)),
                pl.unitPrice != null ? pl.units : null, null, "ConfigurationPrice");
    }

    /* ---------- small helpers ---------- */

    private static EntityRef ref(ProductOfferingDto o) {
        return EntityRef.of(o.getId(), o.getName(), "ProductOffering");
    }

    private ProductSpecificationDto findSpec(ProductOfferingDto offering) {
        EntityRef ref = offering.getProductSpecification();
        if (ref == null || ref.id() == null) {
            return null;
        }
        try {
            return specs.findById(ref.id());
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

    private static String offeringIdOf(ProductConfigurationRequest config) {
        if (config.productOffering() == null || config.productOffering().id() == null) {
            throw new BadRequestException("productOffering.id is required");
        }
        return config.productOffering().id();
    }

    private static BigDecimal amountOf(ProductOfferingPriceDto price) {
        return price.getPrice() == null || price.getPrice().value() == null ? BigDecimal.ZERO : price.getPrice().value();
    }

    private static BigDecimal decimalOf(Object v, BigDecimal dflt) {
        try {
            return v == null ? dflt : new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return dflt;
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** An empty list is left off the wire, as the maps left it off. */
    private static <T> List<T> orNull(List<T> l) {
        return l == null || l.isEmpty() ? null : l;
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
