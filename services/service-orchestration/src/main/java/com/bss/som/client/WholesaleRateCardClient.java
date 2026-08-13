package com.bss.som.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wholesale rate card, read from the catalog's "Wholesale access" products
 * (W2): each owner's access offering carries the per-line wholesale rate (its
 * price) and the layer + owner (its spec facts). Plus the retail fibre monthly, so
 * the margin (retail − wholesale) is computable. Fail-soft to an empty card.
 */
@Component
public class WholesaleRateCardClient {

    private static final Logger log = LoggerFactory.getLogger(WholesaleRateCardClient.class);
    private static final String CAT = "/tmf-api/productCatalogManagement/v4";

    private final RestClient restClient;

    public WholesaleRateCardClient(RestClient.Builder builder, MachineTokenInterceptor tokenInterceptor,
            @Value("${bss.downstream.catalog-base-url:http://localhost:8081}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).requestInterceptor(tokenInterceptor).build();
    }

    /** owner code -> {rate, layer} from the wholesale access offerings. */
    @SuppressWarnings("unchecked")
    public Map<String, Rate> rateCard() {
        Map<String, Rate> card = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> offerings = restClient.get()
                    .uri(CAT + "/productOffering?limit=100").retrieve().body(List.class);
            for (Map<String, Object> off : offerings == null ? List.<Map<String, Object>>of() : offerings) {
                String category = off.get("category") instanceof List<?> cs && !cs.isEmpty()
                        && cs.get(0) instanceof Map<?, ?> c0 ? String.valueOf(c0.get("name")) : "";
                if (!"Wholesale access".equals(category)) {
                    continue;
                }
                String owner = null, layer = null;
                if (off.get("productSpecification") instanceof Map<?, ?> sr && sr.get("id") != null) {
                    Map<String, Object> spec = restClient.get()
                            .uri(CAT + "/productSpecification/{id}", sr.get("id")).retrieve().body(Map.class);
                    for (Map<String, Object> ch : chars(spec)) {
                        if ("accessOwner".equals(ch.get("name"))) {
                            owner = firstValue(ch);
                        } else if ("accessLayer".equals(ch.get("name"))) {
                            layer = firstValue(ch);
                        }
                    }
                }
                Double rate = firstRecurringPrice(off);
                if (owner != null && rate != null) {
                    card.put(owner, new Rate(rate, layer));
                }
            }
        } catch (RestClientException e) {
            log.warn("catalog unreachable, empty wholesale rate card (fail-soft): {}", e.getMessage());
        }
        return card;
    }

    /** The retail fibre monthly, for the margin — the recurring price of the
     *  named retail offering. Null if unavailable. */
    @SuppressWarnings("unchecked")
    public Double retailMonthly(String offeringName) {
        try {
            List<Map<String, Object>> offerings = restClient.get()
                    .uri(CAT + "/productOffering?limit=100").retrieve().body(List.class);
            for (Map<String, Object> off : offerings == null ? List.<Map<String, Object>>of() : offerings) {
                if (offeringName.equals(off.get("name"))) {
                    return firstRecurringPrice(off);
                }
            }
        } catch (RestClientException e) {
            log.warn("catalog unreachable for retail price (fail-soft): {}", e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Double firstRecurringPrice(Map<String, Object> offering) {
        if (!(offering.get("productOfferingPrice") instanceof List<?> refs)) {
            return null;
        }
        for (Object r : refs) {
            if (!(r instanceof Map<?, ?> ref) || ref.get("id") == null) {
                continue;
            }
            try {
                Map<String, Object> price = restClient.get()
                        .uri(CAT + "/productOfferingPrice/{id}", ref.get("id")).retrieve().body(Map.class);
                if (price != null && "recurring".equals(price.get("priceType"))
                        && price.get("price") instanceof Map<?, ?> p && p.get("value") instanceof Number n) {
                    return n.doubleValue();
                }
            } catch (RestClientException ignored) {
                // try the next price ref
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> chars(Map<String, Object> spec) {
        return spec != null && spec.get("productSpecCharacteristic") instanceof List<?> l
                ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static String firstValue(Map<String, Object> ch) {
        if (ch.get("productSpecCharacteristicValue") instanceof List<?> vs && !vs.isEmpty()
                && vs.get(0) instanceof Map<?, ?> v0 && v0.get("value") != null) {
            return String.valueOf(v0.get("value"));
        }
        return null;
    }

    public record Rate(double perLine, String layer) {
    }
}
