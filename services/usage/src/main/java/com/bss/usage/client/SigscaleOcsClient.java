package com.bss.usage.client;

import com.bss.usage.entity.PrepayTask;
import com.bss.usage.repository.PrepayTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * bucket id lands on the right product.
 */
@Component
public class SigscaleOcsClient implements OcsBalanceAdapter {

    private static final Logger log = LoggerFactory.getLogger(SigscaleOcsClient.class);

    public static final String INVENTORY = "/productInventoryManagement/v2";
    public static final String BALANCE = "/balanceManagement/v1";
    static final String JSON_PATCH = "application/json-patch+json";
    public static final double GB = 1_000_000_000d;

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
    public List<Map<String, Object>> subscribersOf(String tenantId, String partyId) {
        RestClient c = client(tenantId);
        if (c == null || partyId == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> p : productsWhere(c, "bssPartyId", partyId)) {
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

    /** The product by id, or null when the OCS no longer has it. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> product(String tenantId, String productId) {
        RestClient c = client(tenantId);
        if (c == null) {
            return null;
        }
        try {
            return c.get().uri(INVENTORY + "/product/{id}", productId).retrieve().body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** The mock-shaped projection every consumer of the seam understands. */
    public Map<String, Object> project(Map<String, Object> p) {
        String productId = String.valueOf(p.get("id"));
        double remaining = remainingGb(p);
        String tenantId = characteristic(p, "bssTenantId");
        double allowance = parse(characteristic(p, "bssAllowanceGB"));
        double granted = allowance + (tenantId == null ? 0 : topupsGb(tenantId, productId));
        double total = granted > 0 ? Math.max(granted, remaining) : remaining;
        Map<String, Object> offering = p.get("productOffering") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String ratePlanId = offering.get("id") == null ? null : String.valueOf(offering.get("id"));
        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("id", productId);
        bucket.put("name", (offering.get("name") == null ? ratePlanId : offering.get("name")) + " data");
        bucket.put("ratePlanId", ratePlanId);
        bucket.put("totalGB", round(total));
        bucket.put("usedGB", round(Math.max(0, total - remaining)));
        bucket.put("rolloverGB", 0);
        bucket.put("rollover", false);
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("id", productId);
        sub.put("tenantId", characteristic(p, "bssTenantId"));
        sub.put("partyId", characteristic(p, "bssPartyId"));
        sub.put("serviceId", characteristic(p, "bssServiceId"));
        sub.put("ratePlanId", ratePlanId);
        sub.put("status", p.get("status") == null ? "active" : p.get("status"));
        sub.put("buckets", List.of(bucket));
        sub.put("provider", "sigscale");
        return sub;
    }

    static final int PAGE = 500;

    /**
     * Products carrying {@code charName = value}. SigScale's TMF630
     * characteristic filter matches on presence, not value (verified on
     * 3.4.73), so the inventory is paged (Range: items=…) and matched here.
     * Fine at operator-of-tens-of-thousands scale; a per-party index would be
     * the next step for millions.
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> productsWhere(RestClient c, String charName, String value) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int page = 0; page < 40; page++) {
            int from = page * PAGE + 1;
            List<Map<String, Object>> products;
            try {
                products = c.get().uri(INVENTORY + "/product")
                        .header("Range", "items=" + from + "-" + (from + PAGE - 1))
                        .retrieve().body(List.class);
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
            for (Map<String, Object> p : products) {
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
                Map<String, Object> payload = mapper.readValue(t.getPayloadJson(), Map.class);
                Object bucket = payload.get("bucket");
                Object amount = payload.get("amount");
                if (bucket instanceof Map<?, ?> b && productId.equals(String.valueOf(b.get("id")))
                        && amount instanceof Map<?, ?> a && a.get("amount") != null) {
                    sum += parse(String.valueOf(a.get("amount")));
                }
            }
        } catch (RuntimeException | java.io.IOException e) {
            log.debug("SigScale OCS: top-up log unreadable for {} ({})", productId, e.getMessage());
        }
        return sum;
    }

    /** The accumulated data balance on a product, in GB. */
    public static double remainingGb(Map<String, Object> product) {
        Object balances = product == null ? null : product.get("balance");
        if (balances instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> b && "octets".equals(b.get("name"))
                        && b.get("totalBalance") instanceof Map<?, ?> t) {
                    return octets(t.get("amount")) / GB;
                }
            }
        }
        return 0;
    }

    /** SigScale renders octets as a number or as "9000000000b". */
    public static double octets(Object amount) {
        if (amount == null) {
            return 0;
        }
        if (amount instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(amount).trim().toLowerCase();
        if (s.endsWith("b")) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String characteristic(Map<String, Object> product, String name) {
        Object chars = product == null ? null : product.get("characteristic");
        if (chars instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && name.equals(m.get("name"))) {
                    return m.get("value") == null ? null : String.valueOf(m.get("value"));
                }
            }
        }
        return null;
    }

    static int characteristicIndex(Map<String, Object> product, String name) {
        Object chars = product == null ? null : product.get("characteristic");
        if (chars instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof Map<?, ?> m && name.equals(m.get("name"))) {
                    return i;
                }
            }
        }
        return -1;
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

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(round(v));
    }
}
