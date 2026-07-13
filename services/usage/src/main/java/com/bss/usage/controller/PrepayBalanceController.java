package com.bss.usage.controller;

import com.bss.usage.client.OcsClient;
import com.bss.usage.exception.BadRequestException;
import com.bss.usage.security.PartyScope;
import com.bss.usage.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF654 Prepay Balance Management — the customer's window onto the
 * counters the OCS keeps: remaining data, what rolled over, and top-ups.
 * A PROJECTION, not a source: the OCS stays the charging master, this
 * facade translates its buckets into the TMF shape the channels speak.
 * (Served by the usage component in v1; splits into its own ODA component
 * with the v2 usage flip.)
 */
@RestController
@RequestMapping("/tmf-api/prepayBalanceManagement/v4")
public class PrepayBalanceController {

    private final OcsClient ocs;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;

    public PrepayBalanceController(OcsClient ocs, PartyScope partyScope, TenantScope tenantScope) {
        this.ocs = ocs;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/bucket")
    public ResponseEntity<List<Map<String, Object>>> buckets(
            @RequestParam(required = false) String relatedPartyId) {
        String party = partyScope.scopedPartyId().orElse(relatedPartyId);
        if (party == null) {
            throw new BadRequestException("relatedPartyId is required for unscoped callers");
        }
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (Map<String, Object> sub : ocs.subscribersOf(tenantScope.currentTenantId(), party)) {
            for (Object o : asList(sub.get("buckets"))) {
                if (!(o instanceof Map<?, ?> raw)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> bucket = (Map<String, Object>) raw;
                double total = number(bucket.get("totalGB")) + number(bucket.get("rolloverGB"));
                double used = number(bucket.get("usedGB"));
                Map<String, Object> dto = new LinkedHashMap<>();
                dto.put("id", bucket.get("id"));
                dto.put("@type", "Bucket");
                dto.put("name", bucket.get("name"));
                dto.put("ratePlanId", bucket.get("ratePlanId"));
                dto.put("subscriberId", sub.get("id"));
                dto.put("serviceId", sub.get("serviceId"));
                dto.put("remainingValue", Map.of(
                        "amount", Math.max(0, round(total - used)), "units", "GB"));
                dto.put("usedValue", Map.of("amount", round(used), "units", "GB"));
                dto.put("rolloverValue", Map.of(
                        "amount", round(number(bucket.get("rolloverGB"))), "units", "GB"));
                dto.put("isRolloverEligible", Boolean.TRUE.equals(bucket.get("rollover")));
                dto.put("relatedParty", List.of(Map.of("id", party, "role", "customer")));
                buckets.add(dto);
            }
        }
        return ResponseEntity.ok(buckets);
    }

    /** TMF654 topupBalance task: credit a bucket — the OCS does the arithmetic. */
    @PostMapping("/topupBalance")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> topup(@RequestBody Map<String, Object> request) {
        String party = partyScope.scopedPartyId().orElseGet(() ->
                request.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                        && parties.get(0) instanceof Map<?, ?> p && p.get("id") != null
                        ? String.valueOf(p.get("id")) : null);
        if (party == null) {
            throw new BadRequestException("relatedParty [{id}] is required for unscoped callers");
        }
        double amount = request.get("amount") instanceof Map<?, ?> money && money.get("amount") != null
                ? number(money.get("amount")) : 0;
        if (amount <= 0) {
            throw new BadRequestException("amount {amount, units} must be positive");
        }
        String bucketId = request.get("bucket") instanceof Map<?, ?> b && b.get("id") != null
                ? String.valueOf(b.get("id")) : null;
        // the party boundary IS the authorization: only own subscribers reachable
        for (Map<String, Object> sub : ocs.subscribersOf(tenantScope.currentTenantId(), party)) {
            boolean match = bucketId == null || asList(sub.get("buckets")).stream()
                    .anyMatch(o -> o instanceof Map<?, ?> raw && bucketId.equals(String.valueOf(raw.get("id"))));
            if (match && ocs.credit(String.valueOf(sub.get("id")), amount)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", UUID.randomUUID().toString());
                result.put("@type", "TopupBalance");
                result.put("status", "done");
                result.put("amount", Map.of("amount", amount, "units", "GB"));
                result.put("relatedParty", List.of(Map.of("id", party, "role", "customer")));
                return ResponseEntity.status(HttpStatus.CREATED).body(result);
            }
        }
        throw new BadRequestException("no charging subscriber found for this party"
                + (ocs.enabled() ? "" : " (no OCS configured in this deployment)"));
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static double number(Object value) {
        try {
            return value == null ? 0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
