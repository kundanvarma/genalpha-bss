package com.bss.usage.client;

import com.bss.usage.dto.OcsBucket;
import com.bss.usage.dto.OcsSubscriber;
import com.bss.usage.entity.PrepayTask;
import com.bss.usage.repository.PrepayTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code sigscale} balance adapter: SigScale OCS over its TM Forum APIs.
 * <ul>
 *   <li>TMF637 product = the subscription the SOM created at activation; our
 *       tenant/party/service ids ride on it as characteristics, and the
 *       product carries its accumulated data balance in octets.</li>
 *   <li>TMF654 balanceTopup credits a bucket on the product; the allowance
 *       bookkeeping ({@code bssAllowanceGB}) grows with it so "used" stays
 *       honest: used = granted − remaining.</li>
 * </ul>
 * One projected bucket per product (id = product id), so a TMF654 top-up by
 * bucket id lands on the right product. The OCS's product is a foreign
 * document ({@link JsonNode}); what the adapter projects is the
 * {@link OcsSubscriber} every consumer of the seam understands.
 */
@Component
public class SigscaleOcsClient implements OcsBalanceAdapter {

    private static final Logger log = LoggerFactory.getLogger(SigscaleOcsClient.class);

    public static final String INVENTORY = "/productInventoryManagement/v2";
    public static final String BALANCE = "/balanceManagement/v1";
    static final String JSON_PATCH = "application/json-patch+json";
    public static final double GB = 1_000_000_000d;
    private static final ParameterizedTypeReference<List<JsonNode>> PRODUCTS = new ParameterizedTypeReference<>() { };

    private final RestClient.Builder builder;
    private final OcsSettings settings;
    private final PrepayTaskRepository tasks;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public SigscaleOcsClient(RestClient.Builder builder, OcsSettings settings, PrepayTaskRepository tasks) {
        this.builder = builder;
        this.settings = settings;
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "sigscale";
    }

    @Override
    public boolean enabled(String tenantId) {
        return settings.forTenant(tenantId).enabled();
    }

    public RestClient client(String tenantId) {
        OcsSettings.Binding b = settings.forTenant(tenantId);
        if (!b.enabled()) {
            return null;
        }
        String key = b.baseUrl() + "|" + b.username();
        return clients.computeIfAbsent(key, k -> {
            String basic = Base64.getEncoder().encodeToString(
                    (b.username() + ":" + b.password()).getBytes(StandardCharsets.UTF_8));
            return builder.clone().baseUrl(b.baseUrl())
                    .defaultHeader("Authorization", "Basic " + basic)
                    .defaultHeader("Accept", "application/json")
                    .build();
        });
    }

    @Override
    public List<OcsSubscriber> subscribersOf(String tenantId, String partyId) {
        RestClient c = client(tenantId);
        if (c == null || partyId == null) {
            return List.of();
        }
        try {
            List<OcsSubscriber> out = new ArrayList<>();
            for (JsonNode p : productsWhere(c, "bssPartyId", partyId)) {
                if (tenantId.equals(characteristic(p, "bssTenantId"))) {
                    out.add(project(p));
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("SigScale OCS: balance read failed for party {} ({}) — answering no balances", partyId, e.getMessage());
            return List.of(); // fail open: no balances beats no page
        }
    }

    @Override
    public boolean credit(String tenantId, String subscriberId, double gb) {
        RestClient c = client(tenantId);
        if (c == null || gb <= 0) {
            return false;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", Map.of("units", "octets", "amount", Math.round(gb * GB)));
            body.put("product", Map.of("id", subscriberId));
            c.post().uri(BALANCE + "/product/{id}/balanceTopup", subscriberId)
                    .header("Content-Type", "application/json").body(body)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.warn("SigScale OCS: top-up of {} GB on product {} failed ({})", gb, subscriberId, e.getMessage());
            return false;
        }
    }

    /* ------------------------------------------------------------- shared */

    /** The product by id (the OCS's own TMF637 document), or null when the OCS no longer has it. */
    public JsonNode product(String tenantId, String productId) {
        RestClient c = client(tenantId);
        if (c == null) {
            return null;
        }
        try {
            return c.get().uri(INVENTORY + "/product/{id}", productId).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** The mock-shaped projection every consumer of the seam understands. */
    public OcsSubscriber project(JsonNode p) {
        String productId = p.path("id").asText();
        double remaining = remainingGb(p);
        String tenantId = characteristic(p, "bssTenantId");
        double allowance = parse(characteristic(p, "bssAllowanceGB"));
        double granted = allowance + (tenantId == null ? 0 : topupsGb(tenantId, productId));
        double total = granted > 0 ? Math.max(granted, remaining) : remaining;
        JsonNode offering = p.path("productOffering");
        String ratePlanId = offering.hasNonNull("id") ? offering.get("id").asText() : null;
        OcsBucket bucket = new OcsBucket(productId,
                (offering.hasNonNull("name") ? offering.get("name").asText() : ratePlanId) + " data",
                ratePlanId, round(total), round(Math.max(0, total - remaining)), 0, false);
        return new OcsSubscriber(productId, characteristic(p, "bssTenantId"), characteristic(p, "bssPartyId"),
                characteristic(p, "bssServiceId"), ratePlanId,
                p.hasNonNull("status") ? p.get("status").asText() : "active",
                List.of(bucket), "sigscale");
    }

    static final int PAGE = 500;

    /**
     * Products carrying {@code charName = value}. SigScale's TMF630
     * characteristic filter matches on presence, not value (verified on
     * 3.4.73), so the inventory is paged (Range: items=…) and matched here.
     * Fine at operator-of-tens-of-thousands scale; a per-party index would be
     * the next step for millions.
     */
    List<JsonNode> productsWhere(RestClient c, String charName, String value) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; page < 40; page++) {
            int from = page * PAGE + 1;
            List<JsonNode> products;
            try {
                products = c.get().uri(INVENTORY + "/product")
                        .header("Range", "items=" + from + "-" + (from + PAGE - 1))
                        .retrieve().body(PRODUCTS);
            } catch (HttpClientErrorException.NotFound e) {
                break;
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 416) {
                    break; // past the end
                }
                throw e;
            }
            if (products == null) {
                break;
            }
            for (JsonNode p : products) {
                if (value.equals(characteristic(p, charName))) {
                    out.add(p);
                }
            }
            if (products.size() < PAGE) {
                break;
            }
        }
        return out;
    }

    /**
     * GB credited onto this product by TMF654 top-ups — read from the task log
     * the facade keeps, never written back to the OCS: patching a SigScale
     * product rewrites it and drops its bucket links (learned the hard way).
     * granted = plan allowance (a characteristic set at creation) + these.
     */
    double topupsGb(String tenantId, String productId) {
        double sum = 0;
        try {
            for (PrepayTask t : tasks.findAllByTenantIdAndResourceTypeOrderByCreatedAtAsc(tenantId, "topupBalance")) {
                JsonNode payload = mapper.readTree(t.getPayloadJson());
                JsonNode bucket = payload.path("bucket");
                JsonNode amount = payload.path("amount");
                if (productId.equals(bucket.path("id").asText()) && amount.hasNonNull("amount")) {
                    sum += parse(amount.get("amount").asText());
                }
            }
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("SigScale OCS: top-up log unreadable for {} ({})", productId, e.getMessage());
        }
        return sum;
    }

    /** The accumulated data balance on a product, in GB. */
    public static double remainingGb(JsonNode product) {
        if (product != null) {
            for (JsonNode b : product.path("balance")) {
                if ("octets".equals(b.path("name").asText()) && b.path("totalBalance").isObject()) {
                    return octets(b.path("totalBalance").get("amount")) / GB;
                }
            }
        }
        return 0;
    }

    /** SigScale renders octets as a number or as "9000000000b". */
    public static double octets(JsonNode amount) {
        if (amount == null || amount.isNull() || amount.isMissingNode()) {
            return 0;
        }
        if (amount.isNumber()) {
            return amount.doubleValue();
        }
        String s = amount.asText().trim().toLowerCase();
        if (s.endsWith("b")) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String characteristic(JsonNode product, String name) {
        if (product != null) {
            for (JsonNode m : product.path("characteristic")) {
                if (name.equals(m.path("name").asText())) {
                    return m.hasNonNull("value") ? m.get("value").asText() : null;
                }
            }
        }
        return null;
    }

    static double parse(String v) {
        try {
            return v == null ? 0 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
