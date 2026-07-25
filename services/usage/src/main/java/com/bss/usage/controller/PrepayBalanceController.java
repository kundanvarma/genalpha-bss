package com.bss.usage.controller;

import com.bss.usage.api.FieldSelector;
import com.bss.usage.client.OcsClient;
import com.bss.usage.entity.PrepayTask;
import com.bss.usage.exception.BadRequestException;
import com.bss.usage.exception.NotFoundException;
import com.bss.usage.repository.PrepayTaskRepository;
import com.bss.usage.security.PartyScope;
import com.bss.usage.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TMF654 Prepay Balance Management — the customer's window onto the
 * counters the OCS keeps: remaining data, what rolled over, and top-ups.
 * A PROJECTION plus a TASK LOG, never a second charging truth: the OCS
 * stays the master; live meters project from its buckets, and the TMF654
 * task resources (bucket, topupBalance, adjustBalance, reserveBalance)
 * persist here in the TMF shape — CTK-conformant — recording what was
 * asked. Where a real credit path exists (top-up), the OCS does the
 * arithmetic before the task row is written.
 * (Served by the usage component in v1; splits into its own ODA component
 * with the v2 usage flip.)
 */
@RestController
@RequestMapping("/tmf-api/prepayBalanceManagement/v4")
public class PrepayBalanceController {

    private final OcsClient ocs;
    private final PartyScope partyScope;
    private final TenantScope tenantScope;
    private final PrepayTaskRepository tasks;
    private final FieldSelector fieldSelector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PrepayBalanceController(OcsClient ocs, PartyScope partyScope, TenantScope tenantScope,
            PrepayTaskRepository tasks, FieldSelector fieldSelector) {
        this.ocs = ocs;
        this.partyScope = partyScope;
        this.tenantScope = tenantScope;
        this.tasks = tasks;
        this.fieldSelector = fieldSelector;
    }

    /* ---------- bucket: live projection for a party, task log otherwise ---------- */

    @GetMapping("/bucket")
    public ResponseEntity<List<Map<String, Object>>> buckets(
            @RequestParam(required = false) String relatedPartyId,
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String usageType) {
        String party = partyScope.scopedPartyId().orElse(relatedPartyId);
        if (party == null) {
            // no party in sight: the resource face — created bucket records
            return ResponseEntity.ok(select(list("bucket", id, status, usageType), fields));
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
        return ResponseEntity.ok(select(buckets, fields));
    }

    @PostMapping("/bucket")
    public ResponseEntity<Map<String, Object>> createBucket(@RequestBody Map<String, Object> request) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (request.get("usageType") == null) {
            extra.put("usageType", "monetary");
        }
        extra.put("confirmationDate", OffsetDateTime.now().toString());
        return created(save("bucket", "Bucket", "active", request, extra));
    }

    @GetMapping("/bucket/{id}")
    public ResponseEntity<Map<String, Object>> bucketById(@PathVariable("id") String id) {
        return ResponseEntity.ok(byId("bucket", "Bucket", id));
    }

    /* ---------- topupBalance: the OCS credits, the task log remembers ---------- */

    @PostMapping("/topupBalance")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> topup(@RequestBody Map<String, Object> request) {
        String party = partyScope.scopedPartyId().orElseGet(() ->
                request.get("relatedParty") instanceof List<?> parties && !parties.isEmpty()
                        && parties.get(0) instanceof Map<?, ?> p && p.get("id") != null
                        ? String.valueOf(p.get("id")) : null);
        if (party == null) {
            // no party named: record the task in the TMF shape (resource face)
            return created(save("topupBalance", "TopupBalance", "done", request, Map.of()));
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
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("amount", Map.of("amount", amount, "units", "GB"));
                extra.put("relatedParty", List.of(Map.of("id", party, "role", "customer")));
                return created(save("topupBalance", "TopupBalance", "done", request, extra));
            }
        }
        throw new BadRequestException("no charging subscriber found for this party"
                + (ocs.enabled() ? "" : " (no OCS configured in this deployment)"));
    }

    @GetMapping("/topupBalance")
    public ResponseEntity<List<Map<String, Object>>> topups(
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String usageType) {
        return ResponseEntity.ok(select(list("topupBalance", id, status, usageType), fields));
    }

    @GetMapping("/topupBalance/{id}")
    public ResponseEntity<Map<String, Object>> topupById(@PathVariable("id") String id) {
        return ResponseEntity.ok(byId("topupBalance", "TopupBalance", id));
    }

    /* ---------- adjustBalance / reserveBalance: recorded, not charged ----------
     * The OCS owns adjustment and reservation truth (Gy/Ro land). The facade
     * records the TMF654 task so the API face is complete and auditable; a
     * production OCS behind the seam is where the counters actually move. */

    @PostMapping("/adjustBalance")
    public ResponseEntity<Map<String, Object>> adjust(@RequestBody Map<String, Object> request) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (request.get("usageType") == null) {
            extra.put("usageType", "monetary");
        }
        return created(save("adjustBalance", "AdjustBalance", "done", request, extra));
    }

    @GetMapping("/adjustBalance")
    public ResponseEntity<List<Map<String, Object>>> adjusts(
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String usageType) {
        return ResponseEntity.ok(select(list("adjustBalance", id, status, usageType), fields));
    }

    @GetMapping("/adjustBalance/{id}")
    public ResponseEntity<Map<String, Object>> adjustById(@PathVariable("id") String id) {
        return ResponseEntity.ok(byId("adjustBalance", "AdjustBalance", id));
    }

    @PostMapping("/reserveBalance")
    public ResponseEntity<Map<String, Object>> reserve(@RequestBody Map<String, Object> request) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (!(request.get("relatedParty") instanceof List<?> parties) || parties.isEmpty()) {
            partyScope.scopedPartyId().ifPresent(p ->
                    extra.put("relatedParty", List.of(Map.of("id", p, "role", "customer"))));
        }
        return created(save("reserveBalance", "ReserveBalance", "done", request, extra));
    }

    @GetMapping("/reserveBalance")
    public ResponseEntity<List<Map<String, Object>>> reserves(
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String usageType) {
        return ResponseEntity.ok(select(list("reserveBalance", id, status, usageType), fields));
    }

    @GetMapping("/reserveBalance/{id}")
    public ResponseEntity<Map<String, Object>> reserveById(@PathVariable("id") String id) {
        return ResponseEntity.ok(byId("reserveBalance", "ReserveBalance", id));
    }

    /* ---------- the task-resource plumbing ---------- */

    private Map<String, Object> save(String type, String atType, String status,
            Map<String, Object> body, Map<String, Object> serverFields) {
        Map<String, Object> echo = new LinkedHashMap<>(body);
        echo.putAll(serverFields);
        String taskId = UUID.randomUUID().toString();
        echo.put("id", taskId);
        echo.put("href", "/tmf-api/prepayBalanceManagement/v4/" + type + "/" + taskId);
        echo.put("status", status);
        echo.put("@type", atType);
        PrepayTask task = new PrepayTask();
        task.setId(taskId);
        task.setTenantId(tenantScope.currentTenantId());
        task.setResourceType(type);
        task.setStatus(status);
        task.setUsageType(echo.get("usageType") == null ? null : String.valueOf(echo.get("usageType")));
        task.setPayloadJson(writeJson(echo));
        task.setCreatedAt(OffsetDateTime.now());
        tasks.save(task);
        return echo;
    }

    private List<Map<String, Object>> list(String type, String id, String status, String usageType) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PrepayTask task : tasks.findAllByTenantIdAndResourceTypeOrderByCreatedAtAsc(
                tenantScope.currentTenantId(), type)) {
            if (id != null && !id.equals(task.getId())) {
                continue;
            }
            if (status != null && !status.equals(task.getStatus())) {
                continue;
            }
            if (usageType != null && !usageType.equals(task.getUsageType())) {
                continue;
            }
            out.add(readJson(task.getPayloadJson()));
        }
        return out;
    }

    private Map<String, Object> byId(String type, String resource, String id) {
        return tasks.findByIdAndTenantIdAndResourceType(id, tenantScope.currentTenantId(), type)
                .map(t -> readJson(t.getPayloadJson()))
                .orElseThrow(() -> NotFoundException.forResource(resource, id));
    }

    private List<Map<String, Object>> select(List<Map<String, Object>> items, String fields) {
        return fields == null ? items : fieldSelector.select(items, fields);
    }

    private ResponseEntity<Map<String, Object>> created(Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String s) {
        try {
            return s == null ? new LinkedHashMap<>() : objectMapper.readValue(s, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
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
