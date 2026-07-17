package com.bss.intelligence.service;

import com.bss.intelligence.client.MachineTokenInterceptor;
import com.bss.intelligence.llm.LlmRouter;
import com.bss.intelligence.security.TenantRegistry;
import com.bss.intelligence.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE PRODUCT ADVISOR: reads the operator's OWN data and the MARKET, and
 * turns both into proposals — never actions. Findings are RECEIPTS
 * first: the top-up attach rate is counted from billing lines, the
 * market gap from the feed's numbers against the catalog's. The
 * tenant's LLM (fail-open) only NARRATES what the arithmetic found.
 * Adopting a finding creates a DRAFT offering — lifecycleStatus
 * "In study" — because the advisor advises and the product owner
 * decides.
 */
@Service
public class ProductAdvisorService {

    private static final Logger log = LoggerFactory.getLogger(ProductAdvisorService.class);
    private static final Pattern GB = Pattern.compile("(\\d+)\\s*GB", Pattern.CASE_INSENSITIVE);

    private final RestClient catalog;
    private final RestClient billing;
    private final RestClient inventory;
    private final RestClient market;
    private final TenantRegistry tenants;
    private final TenantScope tenantScope;
    private final LlmRouter llm;

    public ProductAdvisorService(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8081}") String catalogBase,
            @Value("${bss.downstream.billing-base-url:http://localhost:8088}") String billingBase,
            @Value("${bss.downstream.inventory-base-url:http://localhost:8083}") String inventoryBase,
            TenantRegistry tenants, TenantScope tenantScope, LlmRouter llm) {
        this.catalog = builder.baseUrl(catalogBase).requestInterceptor(tokenInterceptor).build();
        this.billing = builder.baseUrl(billingBase).requestInterceptor(tokenInterceptor).build();
        this.inventory = builder.baseUrl(inventoryBase).requestInterceptor(tokenInterceptor).build();
        this.market = builder.build();
        this.tenants = tenants;
        this.tenantScope = tenantScope;
        this.llm = llm;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findings() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> offerings = pageAll(catalog,
                "/tmf-api/productCatalogManagement/v4/productOffering?lifecycleStatus=Active");
        Set<String> topUpNames = new HashSet<>();
        Map<String, Map<String, Object>> plans = new LinkedHashMap<>();
        for (Map<String, Object> offering : offerings) {
            boolean topUp = offering.get("category") instanceof List<?> cats && cats.stream()
                    .anyMatch(c -> c instanceof Map<?, ?> cat
                            && String.valueOf(cat.get("name")).toLowerCase().contains("top-up"));
            if (topUp || String.valueOf(offering.get("name")).toLowerCase().contains("top-up")) {
                topUpNames.add(String.valueOf(offering.get("name")));
            } else if (!Boolean.TRUE.equals(offering.get("isBundle"))) {
                plans.put(String.valueOf(offering.get("name")), offering);
            }
        }

        /* ---- 1. TOP-UP ATTACH: whose allowance is too small ----
         * counted from the PRODUCT INVENTORY: a customer holding both the
         * plan and a top-up product bought their way past the allowance */
        List<Map<String, Object>> products = pageAll(inventory,
                "/tmf-api/productInventory/v4/product");
        Map<String, Set<String>> planParties = new HashMap<>();
        Set<String> buyers = new HashSet<>();
        for (Map<String, Object> product : products) {
            String name = String.valueOf(product.get("name")).trim();
            String party = null;
            if (product.get("relatedParty") instanceof List<?> parties) {
                for (Object p : parties) {
                    if (p instanceof Map<?, ?> ref
                            && "customer".equalsIgnoreCase(String.valueOf(ref.get("role")))) {
                        party = String.valueOf(ref.get("id"));
                    }
                }
            }
            if (party == null) {
                continue;
            }
            if (plans.containsKey(name)) {
                planParties.computeIfAbsent(name, k -> new HashSet<>()).add(party);
            } else if (topUpNames.contains(name)) {
                buyers.add(party);
            }
        }
        for (Map.Entry<String, Set<String>> plan : planParties.entrySet()) {
            long attached = plan.getValue().stream().filter(buyers::contains).count();
            int base = plan.getValue().size();
            if (base >= 2 && attached * 2 >= base) { // half or more keep topping up
                Map<String, Object> offering = plans.get(plan.getKey());
                int gb = gbOf(plan.getKey());
                Map<String, Object> finding = finding("TOPUP_ATTACH", plan.getKey(),
                        attached + " of " + base + " customers on this plan also bought top-ups"
                                + " — the allowance is priced below their appetite",
                        "Add a bigger tier (or rollover) so the plan fits the usage",
                        Map.of("customersOnPlan", base, "alsoBoughtTopUps", attached));
                finding.put("proposal", draftProposal(plan.getKey() + " XL",
                        gb > 0 ? (gb * 2) + " GB tier born from top-up demand on " + plan.getKey()
                               : "Bigger tier born from top-up demand on " + plan.getKey(),
                        priceOf(offering).multiply(new BigDecimal("1.3"))));
                out.add(finding);
            }
        }

        /* ---- 2. MARKET PRICE GAP: the feed's numbers vs ours ---- */
        TenantRegistry.TenantEntry tenant = tenants.byId(tenantScope.currentTenantId());
        if (tenant != null && tenant.getMarketFeedUrl() != null
                && !tenant.getMarketFeedUrl().isBlank()) {
            try {
                List<Map<String, Object>> rivals = market.get()
                        .uri(tenant.getMarketFeedUrl() + "/offers")
                        .header("Authorization", "Bearer " + tenant.getMarketFeedToken())
                        .retrieve().body(List.class);
                for (Map<String, Object> plan : plans.values()) {
                    String name = String.valueOf(plan.get("name"));
                    int gb = gbOf(name);
                    BigDecimal ours = priceOf(plan);
                    if (gb <= 0 || ours.signum() <= 0) {
                        continue;
                    }
                    for (Map<String, Object> rival : rivals == null ? List.<Map<String, Object>>of() : rivals) {
                        int rivalGb = Integer.parseInt(String.valueOf(rival.getOrDefault("dataGb", "0")));
                        BigDecimal theirs = new BigDecimal(String.valueOf(
                                ((Map<String, Object>) rival.get("price")).get("value")));
                        if (rivalGb == gb && theirs.multiply(new BigDecimal("1.1")).compareTo(ours) < 0) {
                            Map<String, Object> finding = finding("MARKET_PRICE", name,
                                    rival.get("competitor") + " sells " + rival.get("name") + " ("
                                            + rivalGb + " GB) at " + theirs + " vs our " + ours
                                            + " — a price-position gap on the same bucket",
                                    "Consider a counter-tier at a competitive point",
                                    Map.of("ourPrice", ours, "rivalPrice", theirs,
                                            "rival", rival.get("competitor"), "dataGb", rivalGb));
                            finding.put("proposal", draftProposal(name + " Counter",
                                    gb + " GB counter-offer to " + rival.get("competitor"),
                                    theirs));
                            out.add(finding);
                        }
                    }
                }
            } catch (Exception e) {
                // fail-open: no market feed, no market findings — own-data
                // findings stand on their own
                log.warn("market feed unreadable — market findings skipped: {}", e.getMessage());
            }
        }

        /* ---- 3. the tenant's LLM narrates what the arithmetic found ---- */
        if (!out.isEmpty()) {
            try {
                String summary = llm.complete(
                        "You are a telecom product analyst. One tight paragraph, plain language,"
                                + " no invented numbers.",
                        "Summarize these product findings for a product owner: " + out);
                if (summary != null && !summary.isBlank()) {
                    Map<String, Object> narrative = new LinkedHashMap<>();
                    narrative.put("kind", "NARRATIVE");
                    narrative.put("offering", "—");
                    narrative.put("insight", summary.length() > 600
                            ? summary.substring(0, 600) : summary);
                    narrative.put("suggestion", "AI summary of the receipts above — the numbers"
                            + " are computed, never generated");
                    narrative.put("@type", "AdvisorFinding");
                    out.add(0, narrative);
                }
            } catch (Exception e) {
                log.debug("no narrative — the receipts stand alone: {}", e.getMessage());
            }
        }
        return out;
    }

    /** A human clicked "adopt": the proposal becomes a DRAFT offering —
     * "In study", visibly born from the advisor, decided by people. */
    public Map<String, Object> adopt(Map<String, Object> proposal) {
        if (proposal == null || proposal.get("name") == null) {
            throw new com.bss.intelligence.exception.BadRequestException(
                    "a proposal needs at least a name");
        }
        Map<String, Object> price = catalog.post()
                .uri("/tmf-api/productCatalogManagement/v4/productOfferingPrice")
                .header("Content-Type", "application/json")
                .body(Map.of("name", proposal.get("name") + " monthly",
                        "priceType", "recurring", "recurringChargePeriodType", "month",
                        "recurringChargePeriodLength", 1, "lifecycleStatus", "In study",
                        "price", proposal.getOrDefault("price", Map.of("unit", "EUR", "value", 0))))
                .retrieve().body(Map.class);
        Map<String, Object> draft = catalog.post()
                .uri("/tmf-api/productCatalogManagement/v4/productOffering")
                .header("Content-Type", "application/json")
                .body(Map.of("name", proposal.get("name"),
                        "description", proposal.getOrDefault("description",
                                "Born in the Product advisor"),
                        "lifecycleStatus", "In study", "isBundle", false,
                        "productOfferingPrice", List.of(Map.of("id", price.get("id"),
                                "name", proposal.get("name") + " monthly"))))
                .retrieve().body(Map.class);
        log.info("advisor proposal adopted as DRAFT offering {} ('{}', In study)",
                draft.get("id"), proposal.get("name"));
        return Map.of("offeringId", draft.get("id"), "lifecycleStatus", "In study");
    }

    private Map<String, Object> finding(String kind, String offering, String insight,
            String suggestion, Map<String, Object> evidence) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind);
        map.put("offering", offering);
        map.put("insight", insight);
        map.put("suggestion", suggestion);
        map.put("evidence", evidence);
        map.put("@type", "AdvisorFinding");
        return map;
    }

    private Map<String, Object> draftProposal(String name, String description, BigDecimal value) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("name", name);
        proposal.put("description", description);
        proposal.put("price", Map.of("unit", "EUR",
                "value", value.setScale(2, java.math.RoundingMode.HALF_UP)));
        return proposal;
    }

    private static int gbOf(String name) {
        Matcher m = GB.matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** The offering's monthly price, resolved ref-by-ref; 0 when unknown. */
    @SuppressWarnings("unchecked")
    private BigDecimal priceOf(Map<String, Object> offering) {
        if (!(offering.get("productOfferingPrice") instanceof List<?> refs)) {
            return BigDecimal.ZERO;
        }
        for (Object ref : refs) {
            if (!(ref instanceof Map<?, ?> r) || r.get("id") == null) {
                continue;
            }
            try {
                Map<String, Object> price = catalog.get()
                        .uri("/tmf-api/productCatalogManagement/v4/productOfferingPrice/{id}",
                                String.valueOf(r.get("id")))
                        .retrieve().body(Map.class);
                if (price != null && "recurring".equals(price.get("priceType"))
                        && price.get("price") instanceof Map<?, ?> p && p.get("value") != null) {
                    return new BigDecimal(String.valueOf(p.get("value")));
                }
            } catch (Exception ignored) {
                // an unreadable price ref is skipped, not fatal
            }
        }
        return BigDecimal.ZERO;
    }

    /** Every page of a TMF list (limit caps at 100), bounded sanely. */
    private List<Map<String, Object>> pageAll(RestClient client, String uri) {
        String sep = uri.contains("?") ? "&" : "?";
        List<Map<String, Object>> all = new ArrayList<>();
        for (int offset = 0; offset < 20000; offset += 100) {
            List<Map<String, Object>> page = getList(client,
                    uri + sep + "limit=100&offset=" + offset);
            all.addAll(page);
            if (page.size() < 100) {
                break;
            }
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(RestClient client, String uri) {
        try {
            List<Map<String, Object>> body = client.get().uri(uri).retrieve().body(List.class);
            return body == null ? List.of() : body;
        } catch (Exception e) {
            log.warn("advisor source unreadable ({}): {}", uri, e.getMessage());
            return List.of();
        }
    }
}
