package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.client.WholesaleRateCardClient;
import com.bss.som.entity.WholesaleAccessOrder;
import com.bss.som.repository.WholesaleAccessOrderRepository;
import com.bss.som.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The wholesale/open-access face: the access-seeker orders we placed upstream to
 * fibre owners, so a back office (and wholesale billing) can see what we bought and
 * from whom. Read-only; service:read like the rest of the service-order face.
 */
@RestController
public class WholesaleAccessController {

    private static final String RETAIL_FIBRE = "GenAlpha Fiber 1000";

    private final WholesaleAccessOrderRepository wholesaleOrders;
    private final TenantScope tenantScope;
    private final WholesaleRateCardClient rateCard;

    public WholesaleAccessController(WholesaleAccessOrderRepository wholesaleOrders,
            TenantScope tenantScope, WholesaleRateCardClient rateCard) {
        this.wholesaleOrders = wholesaleOrders;
        this.tenantScope = tenantScope;
        this.rateCard = rateCard;
    }

    /**
     * The wholesale settlement: what we OWE each fibre owner for the access lines
     * live on their network (the accounts-payable side open access adds), and the
     * margin it leaves — retail monthly minus the wholesale rate. This is the money
     * the retail business runs on when it sells over someone else's fibre.
     */
    @GetMapping(ApiConstants.ORDER_BASE + "/wholesaleSettlement")
    public ResponseEntity<Map<String, Object>> settlement() {
        String tenant = tenantScope.currentTenantId();
        Map<String, WholesaleRateCardClient.Rate> card = rateCard.rateCard();
        Double retailMonthly = rateCard.retailMonthly(RETAIL_FIBRE);

        // count live access lines per owner
        Map<String, Integer> linesByOwner = new TreeMap<>();
        for (WholesaleAccessOrder w : wholesaleOrders.findByTenantIdAndState(tenant, WholesaleAccessOrder.ACTIVE)) {
            linesByOwner.merge(w.getAccessOwner(), 1, Integer::sum);
        }

        List<Map<String, Object>> statements = new ArrayList<>();
        double totalOwed = 0.0;
        double totalMargin = 0.0;
        int totalLines = 0;
        for (Map.Entry<String, Integer> e : linesByOwner.entrySet()) {
            String owner = e.getKey();
            int lines = e.getValue();
            WholesaleRateCardClient.Rate rate = card.get(owner);
            double perLine = rate == null ? 0.0 : rate.perLine();
            double owed = round(perLine * lines);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("accessOwner", owner);
            s.put("accessLayer", rate == null ? null : rate.layer());
            s.put("activeLines", lines);
            s.put("ratePerLine", perLine);
            s.put("monthlyOwed", owed);
            if (retailMonthly != null && rate != null) {
                s.put("retailMonthlyPerLine", retailMonthly);
                s.put("marginPerLine", round(retailMonthly - perLine));
                totalMargin += round((retailMonthly - perLine) * lines);
            }
            s.put("currency", "EUR");
            statements.add(s);
            totalOwed += owed;
            totalLines += lines;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "WholesaleSettlement");
        out.put("periodType", "month");
        out.put("owner", statements);
        out.put("totalActiveLines", totalLines);
        out.put("totalMonthlyOwed", round(totalOwed));
        out.put("totalMonthlyMargin", round(totalMargin));
        out.put("currency", "EUR");
        return ResponseEntity.ok(out);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @GetMapping(ApiConstants.ORDER_BASE + "/wholesaleAccessOrder")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String productOrderId,
            @RequestParam(required = false) String state) {
        String tenant = tenantScope.currentTenantId();
        List<WholesaleAccessOrder> rows = productOrderId != null
                ? wholesaleOrders.findByTenantIdAndProductOrderId(tenant, productOrderId)
                : state != null
                        ? wholesaleOrders.findByTenantIdAndState(tenant, state)
                        : wholesaleOrders.findByTenantId(tenant);
        return ResponseEntity.ok(rows.stream().map(this::view).toList());
    }

    private Map<String, Object> view(WholesaleAccessOrder w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("productOrderId", w.getProductOrderId());
        m.put("serviceId", w.getServiceId());
        m.put("accessOwner", w.getAccessOwner());
        m.put("accessLayer", w.getAccessLayer());
        m.put("bandwidthMbps", w.getBandwidthMbps());
        m.put("postCode", w.getPostCode());
        m.put("state", w.getState());
        m.put("externalId", w.getExternalId());
        m.put("activatedAt", w.getActivatedAt());
        m.put("createdAt", w.getCreatedAt());
        m.put("@type", "WholesaleAccessOrder");
        return m;
    }
}
