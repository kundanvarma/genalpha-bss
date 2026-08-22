package com.bss.intelligence.sim;

import com.bss.intelligence.api.ApiConstants;
import com.bss.intelligence.exception.BadRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P4 — THE PROSPECT SIMULATOR: "your business on this BSS" from a price list
 * and an assumed base mix, in the first meeting. UNLIKE every other simulator
 * here, this one touches NO real data — every number is a stated assumption,
 * and the report says so louder than any of its numbers. A pre-sales
 * calculator with the house honesty rules, not a forecast.
 */
@RestController
@RequestMapping(ApiConstants.BASE_PATH + "/simulate/prospect")
public class ProspectSimController {

    private final PriceSimService sims;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public ProspectSimController(PriceSimService sims,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.sims = sims;
        this.objectMapper = objectMapper;
    }

    @org.springframework.web.bind.annotation.GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(sims.list("ProspectSimulation"));
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> simulate(@RequestBody Map<String, Object> request) {
        if (!(request.get("offerings") instanceof List<?> rawOfferings) || rawOfferings.isEmpty()) {
            throw new BadRequestException(
                    "offerings [{name, monthlyPrice, subscribers, allowanceGb?}] are required");
        }
        BigDecimal dataRate = request.get("wholesaleDataRatePerGb") == null ? null
                : new BigDecimal(String.valueOf(request.get("wholesaleDataRatePerGb")));
        String currency = request.get("currency") == null ? "" : String.valueOf(request.get("currency"));

        List<Map<String, Object>> lines = new ArrayList<>();
        BigDecimal annualRevenue = BigDecimal.ZERO;
        BigDecimal annualCostCeiling = BigDecimal.ZERO;
        long totalSubs = 0;
        for (Object raw : rawOfferings) {
            Map<String, Object> o = (Map<String, Object>) raw;
            if (o.get("name") == null || o.get("monthlyPrice") == null || o.get("subscribers") == null) {
                throw new BadRequestException("each offering needs name, monthlyPrice and subscribers");
            }
            BigDecimal price = new BigDecimal(String.valueOf(o.get("monthlyPrice")));
            long subs = Long.parseLong(String.valueOf(o.get("subscribers")));
            BigDecimal revenue = price.multiply(BigDecimal.valueOf(subs)).multiply(BigDecimal.valueOf(12));
            annualRevenue = annualRevenue.add(revenue);
            totalSubs += subs;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("name", o.get("name"));
            line.put("subscribers", subs);
            line.put("monthlyPrice", price);
            line.put("annualRevenue", revenue.setScale(2, RoundingMode.HALF_UP));
            if (dataRate != null && o.get("allowanceGb") != null) {
                BigDecimal cost = new BigDecimal(String.valueOf(o.get("allowanceGb")))
                        .multiply(dataRate);
                BigDecimal annualCost = cost.multiply(BigDecimal.valueOf(subs)).multiply(BigDecimal.valueOf(12));
                annualCostCeiling = annualCostCeiling.add(annualCost);
                line.put("wholesaleCostCeilingPerSub", cost.setScale(2, RoundingMode.HALF_UP));
                line.put("marginPerSub", price.subtract(cost).setScale(2, RoundingMode.HALF_UP));
            }
            lines.add(line);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@type", "ProspectSimulation");
        out.put("lines", lines);
        out.put("totalSubscribers", totalSubs);
        out.put("annualRevenue", annualRevenue.setScale(2, RoundingMode.HALF_UP));
        if (annualCostCeiling.signum() > 0) {
            out.put("annualWholesaleCostCeiling", annualCostCeiling.setScale(2, RoundingMode.HALF_UP));
            out.put("annualGrossMarginFloor",
                    annualRevenue.subtract(annualCostCeiling).setScale(2, RoundingMode.HALF_UP));
        }
        if (!currency.isBlank()) {
            out.put("currency", currency);
        }
        out.put("assumptions", List.of(
                "EVERY number here is a stated assumption — no real subscriber, usage or billing data was read",
                "cost ceiling assumes full-allowance burn at the given wholesale data rate; real burn is lower",
                "flat base: no growth, churn or seasonality modeled"));
        String name = request.get("name") == null
                ? "Prospect: " + totalSubs + " subs" : String.valueOf(request.get("name"));
        try {
            sims.saveReport(name, objectMapper.writeValueAsString(request), out);
        } catch (Exception e) {
            // an unsaveable receipt does not block the answer
        }
        return ResponseEntity.ok(out);
    }
}
