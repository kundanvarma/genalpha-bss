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
import java.util.List;

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
    public ResponseEntity<List<SavedReport>> list() {
        return ResponseEntity.ok(sims.list("ProspectSimulation"));
    }

    @PostMapping
    public ResponseEntity<ProspectSimulation> simulate(@RequestBody ProspectSimulation.Request request) {
        List<ProspectSimulation.Request.Offering> rawOfferings = request.offerings();
        if (rawOfferings == null || rawOfferings.isEmpty()) {
            throw new BadRequestException(
                    "offerings [{name, monthlyPrice, subscribers, allowanceGb?}] are required");
        }
        BigDecimal dataRate = request.wholesaleDataRatePerGb();
        String currency = request.currency() == null ? "" : request.currency();

        List<ProspectSimulation.Line> lines = new ArrayList<>();
        BigDecimal annualRevenue = BigDecimal.ZERO;
        BigDecimal annualCostCeiling = BigDecimal.ZERO;
        long totalSubs = 0;
        for (ProspectSimulation.Request.Offering o : rawOfferings) {
            if (o == null || o.name() == null || o.monthlyPrice() == null || o.subscribers() == null) {
                throw new BadRequestException("each offering needs name, monthlyPrice and subscribers");
            }
            BigDecimal price = o.monthlyPrice();
            long subs = o.subscribers();
            BigDecimal revenue = price.multiply(BigDecimal.valueOf(subs)).multiply(BigDecimal.valueOf(12));
            annualRevenue = annualRevenue.add(revenue);
            totalSubs += subs;
            BigDecimal cost = null;
            if (dataRate != null && o.allowanceGb() != null) {
                cost = o.allowanceGb().multiply(dataRate);
                BigDecimal annualCost = cost.multiply(BigDecimal.valueOf(subs)).multiply(BigDecimal.valueOf(12));
                annualCostCeiling = annualCostCeiling.add(annualCost);
            }
            lines.add(new ProspectSimulation.Line(o.name(), subs, price,
                    revenue.setScale(2, RoundingMode.HALF_UP),
                    cost == null ? null : cost.setScale(2, RoundingMode.HALF_UP),
                    cost == null ? null : price.subtract(cost).setScale(2, RoundingMode.HALF_UP)));
        }
        boolean costed = annualCostCeiling.signum() > 0;
        ProspectSimulation out = new ProspectSimulation("ProspectSimulation", lines, totalSubs,
                annualRevenue.setScale(2, RoundingMode.HALF_UP),
                costed ? annualCostCeiling.setScale(2, RoundingMode.HALF_UP) : null,
                costed ? annualRevenue.subtract(annualCostCeiling).setScale(2, RoundingMode.HALF_UP) : null,
                currency.isBlank() ? null : currency,
                List.of(
                "EVERY number here is a stated assumption — no real subscriber, usage or billing data was read",
                "cost ceiling assumes full-allowance burn at the given wholesale data rate; real burn is lower",
                "flat base: no growth, churn or seasonality modeled"),
                null, null);
        String name = request.name() == null ? "Prospect: " + totalSubs + " subs" : request.name();
        try {
            PriceSimService.Receipt receipt = sims.saveReport(name, objectMapper.writeValueAsString(request), out);
            out = out.saved(receipt.id(), receipt.name());
        } catch (Exception e) {
            // an unsaveable receipt does not block the answer
        }
        return ResponseEntity.ok(out);
    }
}
