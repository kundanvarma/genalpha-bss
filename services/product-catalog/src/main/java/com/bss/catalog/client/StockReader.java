package com.bss.catalog.client;

import com.bss.catalog.dto.Availability;
import com.bss.catalog.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the stock service knows about an offering, read for the configurator so a
 * channel can grey out a colour that is gone before the order is refused. TMF687
 * stock rows are per `stockedProduct` (an offering, optionally with the
 * characteristic values that make a variant), so availability is a list of
 * {characteristics, available}. Fail-soft: no stock service, no stock rows, or an
 * outage all read as "not stock-managed" — configuring must never break because
 * the warehouse is quiet.
 */
@Component
public class StockReader {

    private static final Logger log = LoggerFactory.getLogger(StockReader.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() { };

    private final RestClient client;
    private final TenantScope tenantScope;

    public StockReader(RestClient.Builder builder, TenantScope tenantScope, @Value("${bss.downstream.stock-base-url:}") String baseUrl) {
        this.tenantScope = tenantScope;
        this.client = baseUrl == null || baseUrl.isBlank() ? null : builder.clone().baseUrl(baseUrl).build();
    }

    /** The stocked variants of an offering and what is left of each, or {@link Availability#NONE} when not stock-managed. */
    public Availability availability(String offeringId) {
        if (client == null) {
            return Availability.NONE;
        }
        try {
            // the stock service keys its rows by tenant; an internal read says which tenant is asking (the gateway
            // strips inbound copies of this header, so it is ours to set here and nowhere else)
            List<Map<String, Object>> stocks = client.get()
                    .uri("/tmf-api/productStockManagement/v4/productStock?productOfferingId={id}&limit=100", offeringId)
                    .header("X-Tenant-Id", tenantScope.currentTenantId())
                    .retrieve().body(LIST);
            if (stocks == null || stocks.isEmpty()) {
                return Availability.NONE;
            }
            List<Availability.Row> rows = new ArrayList<>();
            for (Map<String, Object> s : stocks) {
                Map<String, String> chars = new LinkedHashMap<>();
                Object stocked = s.get("stockedProduct");
                if (stocked instanceof Map<?, ?> sp && sp.get("productCharacteristic") instanceof List<?> pcs) {
                    for (Object pc : pcs) {
                        if (pc instanceof Map<?, ?> m && m.get("name") != null) {
                            chars.put(String.valueOf(m.get("name")), String.valueOf(m.get("value")));
                        }
                    }
                }
                int available = 0;
                if (s.get("availableQuantity") instanceof Map<?, ?> aq && aq.get("amount") != null) {
                    available = (int) Double.parseDouble(String.valueOf(aq.get("amount")));
                }
                rows.add(new Availability.Row(chars, available));
            }
            return new Availability(true, rows);
        } catch (RuntimeException e) {
            log.warn("stock unavailable for offering {}: {}", offeringId, e.getMessage());
            return Availability.NONE;
        }
    }

    /** Can this characteristic value be picked? null = stock says nothing about it (not managed per this variant). */
    public Boolean selectable(Availability availability, String name, Object value) {
        if (!availability.managed() || value == null) {
            return null;
        }
        boolean anyRowForName = false;
        int total = 0;
        for (Availability.Row row : availability.rows()) {
            Map<String, String> chars = row.characteristics();
            if (!chars.containsKey(name)) {
                continue;
            }
            anyRowForName = true;
            if (String.valueOf(value).equals(chars.get(name))) {
                total += row.available();
            }
        }
        return anyRowForName ? total > 0 : null;
    }

    /** A shortage message for these picks and quantity, or null when stock allows them. */
    public String shortage(Availability availability, Map<String, String> picks, int quantity) {
        if (!availability.managed()) {
            return null;
        }
        Availability.Row best = null;
        int bestMatch = -1;
        for (Availability.Row row : availability.rows()) {
            Map<String, String> chars = row.characteristics();
            boolean matches = true;
            for (Map.Entry<String, String> c : chars.entrySet()) {
                if (!c.getValue().equals(picks.get(c.getKey()))) {
                    matches = false;
                    break;
                }
            }
            if (matches && chars.size() > bestMatch) {
                best = row;
                bestMatch = chars.size();
            }
        }
        if (best == null) {
            return null; // no row describes this combination: not managed at that grain
        }
        int available = best.available();
        if (available < quantity) {
            Map<String, String> chars = best.characteristics();
            String what = chars.isEmpty() ? "this product" : chars.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).reduce((a, b) -> a + ", " + b).orElse("");
            return available == 0 ? what + " is out of stock" : "only " + available + " of " + what + " left, " + quantity + " requested";
        }
        return null;
    }
}
