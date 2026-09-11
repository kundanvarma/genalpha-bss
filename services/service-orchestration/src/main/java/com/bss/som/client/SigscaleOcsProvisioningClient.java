package com.bss.som.client;

import com.bss.som.entity.ResourceAssignment;
import com.bss.som.repository.ResourceAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code sigscale} OCS adapter: SigScale OCS (open source, Apache-2.0)
 * over the TM Forum APIs it exposes natively —
 * <ul>
 *   <li>TMF620 product offering = the RATE PLAN. It lives in the OCS's own
 *       tooling (an offering whose recurring price carries a data allowance
 *       and whose usage price rates overage); the catalog references it by
 *       name through {@code chargingSpecId}. Missing there = not provisioned,
 *       logged, reconciled by ops — the BSS never invents charging.</li>
 *   <li>TMF637 product = the SUBSCRIPTION: created on the offering, it
 *       inherits the allowance bucket; our tenant/party/service ids ride as
 *       characteristics so the balance face can find it again.</li>
 *   <li>TMF638 service = the IDENTITY the network charges against over
 *       Diameter Gy/Ro (Subscription-Id = MSISDN). Suspend = service
 *       disabled; plan change = new product, service re-pointed, old product
 *       retired.</li>
 * </ul>
 * Fail-open like every seam: an unreachable OCS is a warning, never a
 * blocked activation.
 */
@Component
public class SigscaleOcsProvisioningClient implements OcsProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(SigscaleOcsProvisioningClient.class);

    static final String CATALOG = "/productCatalogManagement/v2";
    static final String INVENTORY = "/productInventoryManagement/v2";
    static final String SERVICES = "/serviceInventoryManagement/v2";
    static final String JSON_PATCH = "application/json-patch+json";

    private final RestClient.Builder builder;
    private final OcsSettings settings;
    private final ResourceAssignmentRepository assignments;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public SigscaleOcsProvisioningClient(RestClient.Builder builder, OcsSettings settings,
            ResourceAssignmentRepository assignments) {
        this.builder = builder;
        this.settings = settings;
        this.assignments = assignments;
    }

    @Override
    public String name() {
        return "sigscale";
    }

    @Override
    public boolean enabledFor(String tenantId) {
        return settings.forTenant(tenantId).enabled();
    }

    private RestClient client(String tenantId) {
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

    /* ------------------------------------------------------------------ seam */

    @Override
    public void provision(String tenantId, String partyId, String serviceId, String chargingSpecId) {
        provision(tenantId, partyId, serviceId, chargingSpecId, List.of());
    }

    @Override
    public void provision(String tenantId, String partyId, String serviceId, String chargingSpecId,
            List<String> zeroRatedApps) {
        RestClient c = client(tenantId);
        if (c == null) {
            return;
        }
        try {
            Map<String, Object> offer = offering(c, chargingSpecId);
            if (offer == null) {
                log.warn("SigScale OCS: rate plan '{}' is not an offering in the OCS — service {} not provisioned, reconcile later",
                        chargingSpecId, serviceId);
                return;
            }
            Map<String, Object> existing = productOf(c, tenantId, serviceId);
            if (existing != null) {
                log.info("SigScale OCS: service {} already has product {} — provisioning is idempotent",
                        serviceId, existing.get("id"));
                return;
            }
            Map<String, Object> product = createProduct(c, tenantId, partyId, serviceId, chargingSpecId,
                    allowanceGbOf(offer), zeroRatedApps);
            String identity = identityOf(tenantId, serviceId);
            linkService(c, identity, String.valueOf(product.get("id")));
            log.info("SigScale OCS: service {} provisioned as product {} on '{}' ({} GB), charging identity {}{}",
                    serviceId, product.get("id"), chargingSpecId, allowanceGbOf(offer), identity,
                    zeroRatedApps == null || zeroRatedApps.isEmpty() ? "" : " zero-rating " + zeroRatedApps);
        } catch (RuntimeException e) {
            log.warn("SigScale OCS provisioning failed for service {} ({}) — activation proceeds, reconcile later",
                    serviceId, e.getMessage());
        }
    }

    @Override
    public void changeRatePlan(String tenantId, String serviceId, String chargingSpecId) {
        RestClient c = client(tenantId);
        if (c == null) {
            return;
        }
        try {
            Map<String, Object> old = productOf(c, tenantId, serviceId);
            if (old == null) {
                log.warn("SigScale OCS: no product for service {} — plan change not mirrored", serviceId);
                return;
            }
            Map<String, Object> offer = offering(c, chargingSpecId);
            if (offer == null) {
                log.warn("SigScale OCS: rate plan '{}' is not an offering in the OCS — plan change for service {} not mirrored",
                        chargingSpecId, serviceId);
                return;
            }
            // a new plan grants its own allowance; the old counter retires with its product
            String freshId = replaceProduct(c, tenantId, serviceId, old, chargingSpecId,
                    characteristic(old, "bssPartyId"), allowanceGbOf(offer), 0);
            log.info("SigScale OCS: service {} moved from product {} to {} on '{}'", serviceId, old.get("id"),
                    freshId, chargingSpecId);
        } catch (RuntimeException e) {
            log.warn("SigScale OCS rate-plan change failed for service {} ({}) — reconcile later",
                    serviceId, e.getMessage());
        }
    }

    @Override
    public void suspend(String tenantId, String serviceId) {
        setEnabled(tenantId, serviceId, false);
    }

    @Override
    public void resume(String tenantId, String serviceId) {
        setEnabled(tenantId, serviceId, true);
    }

    @Override
    public void transfer(String tenantId, String serviceId, String newPartyId) {
        RestClient c = client(tenantId);
        if (c == null) {
            return;
        }
        try {
            Map<String, Object> product = productOf(c, tenantId, serviceId);
            if (product == null) {
                log.warn("SigScale OCS: no product for service {} — transfer not mirrored", serviceId);
                return;
            }
            // a SigScale product is never patched (a patch rewrites it and drops
            // its bucket links): the line's subscription is re-created under the
            // new owner on the SAME plan, and its remaining data follows it
            double allowance = parse(characteristic(product, "bssAllowanceGB"));
            String freshId = replaceProduct(c, tenantId, serviceId, product,
                    String.valueOf(((Map<?, ?>) product.get("productOffering")).get("id")),
                    newPartyId, allowance, remainingOctets(product));
            log.info("SigScale OCS: service {} now charges to party {} (product {} → {})", serviceId, newPartyId,
                    product.get("id"), freshId);
        } catch (RuntimeException e) {
            log.warn("SigScale OCS transfer failed for service {} ({}) — reconcile later", serviceId, e.getMessage());
        }
    }

    /* ------------------------------------------------------------- mechanics */

    /**
     * Replace a line's subscription: a fresh product on {@code offeringName}
     * under {@code partyId}, the charging identity re-pointed, an optional
     * carry of remaining octets granted onto the new product, the old product
     * (and its buckets) deleted. Returns the new product id.
     */
    private String replaceProduct(RestClient c, String tenantId, String serviceId, Map<String, Object> old,
            String offeringName, String partyId, double allowanceGb, double carryOctets) {
        List<String> zero = splitCsv(characteristic(old, "bssZeroRatedApps"));
        Map<String, Object> fresh = createProduct(c, tenantId, partyId, serviceId, offeringName, allowanceGb, zero);
        String freshId = String.valueOf(fresh.get("id"));
        List<String> identities = realizingServices(old);
        for (String identity : identities) {
            patch(c, SERVICES + "/service/" + identity,
                    List.of(Map.of("op", "replace", "path", "/product", "value", freshId)));
        }
        if (identities.isEmpty()) {
            linkService(c, identityOf(tenantId, serviceId), freshId);
        }
        if (carryOctets > 0) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", Map.of("units", "octets", "amount", Math.round(carryOctets)));
            body.put("product", Map.of("id", freshId));
            c.post().uri("/balanceManagement/v1/product/{id}/balanceTopup", freshId)
                    .header("Content-Type", "application/json").body(body).retrieve().toBodilessEntity();
        }
        c.delete().uri(INVENTORY + "/product/{id}", old.get("id")).retrieve().toBodilessEntity();
        return freshId;
    }

    /** The product's accumulated data balance in octets (SigScale renders a number or "Nb"). */
    static double remainingOctets(Map<String, Object> product) {
        Object balances = product == null ? null : product.get("balance");
        if (balances instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> b && "octets".equals(b.get("name"))
                        && b.get("totalBalance") instanceof Map<?, ?> t && t.get("amount") != null) {
                    String s = String.valueOf(t.get("amount")).trim().toLowerCase();
                    if (s.endsWith("b")) {
                        s = s.substring(0, s.length() - 1);
                    }
                    try {
                        return Double.parseDouble(s);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
        }
        return 0;
    }

    private static double parse(String v) {
        try {
            return v == null ? 0 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void setEnabled(String tenantId, String serviceId, boolean enabled) {
        RestClient c = client(tenantId);
        if (c == null) {
            return;
        }
        try {
            Map<String, Object> product = productOf(c, tenantId, serviceId);
            if (product == null) {
                log.warn("SigScale OCS: no product for service {} — {} not mirrored", serviceId,
                        enabled ? "resume" : "suspend");
                return;
            }
            for (String identity : realizingServices(product)) {
                patch(c, SERVICES + "/service/" + identity,
                        List.of(Map.of("op", "replace", "path", "/isServiceEnabled", "value", enabled)));
            }
            log.info("SigScale OCS: charging {} for service {}", enabled ? "resumed" : "suspended", serviceId);
        } catch (RuntimeException e) {
            log.warn("SigScale OCS {} failed for service {} ({}) — reconcile later",
                    enabled ? "resume" : "suspend", serviceId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> offering(RestClient c, String name) {
        try {
            return c.get().uri(CATALOG + "/productOffering/{id}", name).retrieve().body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createProduct(RestClient c, String tenantId, String partyId, String serviceId,
            String offeringName, double allowanceGb, List<String> zeroRatedApps) {
        List<Map<String, Object>> chars = new ArrayList<>();
        chars.add(Map.of("name", "bssTenantId", "value", tenantId));
        chars.add(Map.of("name", "bssPartyId", "value", partyId == null ? "" : partyId));
        chars.add(Map.of("name", "bssServiceId", "value", serviceId));
        chars.add(Map.of("name", "bssAllowanceGB", "value", trim(allowanceGb)));
        if (zeroRatedApps != null && !zeroRatedApps.isEmpty()) {
            chars.add(Map.of("name", "bssZeroRatedApps", "value", String.join(",", zeroRatedApps)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productOffering", Map.of("id", offeringName, "name", offeringName,
                "href", CATALOG + "/productOffering/" + offeringName));
        body.put("characteristic", chars);
        Map<String, Object> product = c.post().uri(INVENTORY + "/product")
                .header("Content-Type", "application/json").body(body)
                .retrieve().body(Map.class);
        if (product == null || product.get("id") == null) {
            throw new IllegalStateException("OCS returned no product id");
        }
        return product;
    }

    private void linkService(RestClient c, String identity, String productId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", identity);
        body.put("state", "active");
        body.put("isServiceEnabled", true);
        body.put("product", productId);
        body.put("serviceCharacteristic", List.of(
                Map.of("name", "serviceIdentity", "value", identity),
                Map.of("name", "servicePassword", "value", UUID.randomUUID().toString().replace("-", "").substring(0, 16)),
                Map.of("name", "multiSession", "value", true)));
        try {
            c.post().uri(SERVICES + "/service").header("Content-Type", "application/json").body(body)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT || e.getStatusCode() == HttpStatus.BAD_REQUEST
                    || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                // the identity is already known (a re-activation): re-point it
                patch(c, SERVICES + "/service/" + identity,
                        List.of(Map.of("op", "replace", "path", "/product", "value", productId),
                                Map.of("op", "replace", "path", "/isServiceEnabled", "value", true)));
                return;
            }
            throw e;
        }
    }

    /** JSON Patch with the optimistic-lock etag SigScale insists on. */
    private void patch(RestClient c, String path, List<Map<String, Object>> ops) {
        ResponseEntity<Void> head = c.get().uri(path).retrieve().toBodilessEntity();
        String etag = head.getHeaders().getFirst("Etag");
        RestClient.RequestBodySpec req = c.patch().uri(path).header("Content-Type", JSON_PATCH);
        if (etag != null) {
            req = req.header("If-Match", etag);
        }
        req.body(ops).retrieve().toBodilessEntity();
    }

    /**
     * The line's subscription in the OCS. The charging identity (MSISDN) is
     * the key: TMF638 service → its product → verified against our ids. Falls
     * back to a scan of the inventory when the identity is not on file (a
     * line provisioned before it had a number). SigScale's characteristic
     * filter matches on presence, not value (verified on 3.4.73), so the
     * value check is always ours.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> productOf(RestClient c, String tenantId, String serviceId) {
        String identity = identityOf(tenantId, serviceId);
        try {
            Map<String, Object> service = c.get().uri(SERVICES + "/service/{id}", identity).retrieve().body(Map.class);
            if (service != null && service.get("product") != null) {
                Map<String, Object> p = c.get().uri(INVENTORY + "/product/{id}", service.get("product"))
                        .retrieve().body(Map.class);
                if (p != null && serviceId.equals(characteristic(p, "bssServiceId"))
                        && tenantId.equals(characteristic(p, "bssTenantId"))) {
                    return p;
                }
            }
        } catch (HttpClientErrorException.NotFound e) {
            // no such identity yet: scan
        }
        for (int page = 0; page < 40; page++) {
            List<Map<String, Object>> products = productPage(c, page);
            if (products == null) {
                return null;
            }
            for (Map<String, Object> p : products) {
                if (serviceId.equals(characteristic(p, "bssServiceId")) && tenantId.equals(characteristic(p, "bssTenantId"))) {
                    return p;
                }
            }
            if (products.size() < PAGE) {
                return null;
            }
        }
        return null;
    }

    static final int PAGE = 500;

    /** One page of the product inventory (SigScale ranges are 1-based, inclusive). */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> productPage(RestClient c, int page) {
        int from = page * PAGE + 1;
        int to = from + PAGE - 1;
        try {
            return c.get().uri(INVENTORY + "/product").header("Range", "items=" + from + "-" + to)
                    .retrieve().body(List.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 416) {
                return null; // past the end
            }
            throw e;
        }
    }

    /** The identity the network presents on Gy: the line's MSISDN (digits), else our service id. */
    private String identityOf(String tenantId, String serviceId) {
        for (ResourceAssignment a : assignments.findByTenantIdAndServiceId(tenantId, serviceId)) {
            String digits = a.getValue() == null ? "" : a.getValue().replaceAll("[^0-9]", "");
            if (digits.length() >= 8) {
                return digits;
            }
        }
        return serviceId;
    }

    /* ------------------------------------------------------------- helpers */

    @SuppressWarnings("unchecked")
    static String characteristic(Map<String, Object> product, String name) {
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

    static List<String> realizingServices(Map<String, Object> product) {
        List<String> ids = new ArrayList<>();
        Object rs = product == null ? null : product.get("realizingService");
        if (rs instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("id") != null) {
                    ids.add(String.valueOf(m.get("id")));
                }
            }
        }
        return ids;
    }

    /** The data allowance an offering grants: the recurring price's alteration
     * in octets ("10000000000b"), as GB. 0 when the plan carries none. */
    static double allowanceGbOf(Map<String, Object> offer) {
        Object prices = offer == null ? null : offer.get("productOfferingPrice");
        double gb = 0;
        if (prices instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> p)) {
                    continue;
                }
                Object alt = p.get("productOfferPriceAlteration");
                if (alt instanceof Map<?, ?> a && a.get("unitOfMeasure") != null) {
                    gb += octetsToGb(String.valueOf(a.get("unitOfMeasure")));
                }
            }
        }
        return gb;
    }

    static double octetsToGb(String unitOfMeasure) {
        String s = unitOfMeasure.trim().toLowerCase();
        try {
            if (s.endsWith("b")) {
                return Double.parseDouble(s.substring(0, s.length() - 1)) / 1_000_000_000d;
            }
            if (s.endsWith("g")) {
                return Double.parseDouble(s.substring(0, s.length() - 1));
            }
            if (s.endsWith("m")) {
                return Double.parseDouble(s.substring(0, s.length() - 1)) / 1000d;
            }
            return Double.parseDouble(s) / 1_000_000_000d;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }
}
