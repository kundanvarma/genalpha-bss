package com.bss.usage.controller;

import com.bss.usage.api.FieldSelector;
import com.bss.usage.client.OcsClient;
import com.bss.usage.dto.Amount;
import com.bss.usage.dto.OcsBucket;
import com.bss.usage.dto.OcsSubscriber;
import com.bss.usage.dto.PrepayBucketView;
import com.bss.usage.dto.RelatedPartyRef;
import com.bss.usage.entity.PrepayTask;
import com.bss.usage.exception.BadRequestException;
import com.bss.usage.exception.NotFoundException;
import com.bss.usage.repository.PrepayTaskRepository;
import com.bss.usage.security.PartyScope;
import com.bss.usage.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.UUID;

/**
 * TMF654 Prepay Balance Management — the customer's window onto the
 * counters the OCS keeps: remaining data, what rolled over, and top-ups.
 * A PROJECTION plus a TASK LOG, never a second charging truth: the OCS
 * stays the master; live meters project from its buckets, and the TMF654
 * task resources (bucket, topupBalance, adjustBalance, reserveBalance)
 * persist here in the TMF shape — CTK-conformant — recording what was
 * asked. A task is the caller's document echoed back with the server's keys
 * laid over it, so it is stored and answered as an open document
 * ({@link ObjectNode}); the live bucket projection is a record.
 * Where a real credit path exists (top-up), the OCS does the arithmetic
 * before the task row is written.
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
    public ResponseEntity<List<?>> buckets(
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
        List<PrepayBucketView> buckets = new ArrayList<>();
        for (OcsSubscriber sub : ocs.subscribersOf(tenantScope.currentTenantId(), party)) {
            for (OcsBucket bucket : sub.bucketList()) {
                double total = bucket.totalGB() + bucket.rolloverGB();
                double used = bucket.usedGB();
                buckets.add(new PrepayBucketView(bucket.id(), "Bucket", bucket.name(), bucket.ratePlanId(),
                        sub.id(), sub.serviceId(),
                        new Amount(Math.max(0, round(total - used)), "GB"),
                        new Amount(round(used), "GB"),
                        new Amount(round(bucket.rolloverGB()), "GB"),
                        bucket.rollover(),
                        List.of(RelatedPartyRef.customer(party))));
            }
        }
        return ResponseEntity.ok(select(buckets, fields));
    }

    @PostMapping("/bucket")
    public ResponseEntity<ObjectNode> createBucket(@RequestBody ObjectNode request) {
        ObjectNode extra = objectMapper.createObjectNode();
        if (!request.hasNonNull("usageType")) {
            extra.put("usageType", "monetary");
        }
        extra.put("confirmationDate", OffsetDateTime.now().toString());
        return created(save("bucket", "Bucket", "active", request, extra));
    }

    @GetMapping("/bucket/{id}")
    public ResponseEntity<ObjectNode> bucketById(@PathVariable("id") String id) {
        return ResponseEntity.ok(byId("bucket", "Bucket", id));
    }

    /* ---------- topupBalance: the OCS credits, the task log remembers ---------- */

    @PostMapping("/topupBalance")
    public ResponseEntity<ObjectNode> topup(@RequestBody ObjectNode request) {
        JsonNode firstParty = request.path("relatedParty").path(0);
        String party = partyScope.scopedPartyId().orElse(
                firstParty.hasNonNull("id") ? firstParty.get("id").asText() : null);
        if (party == null) {
            // no party named: record the task in the TMF shape (resource face)
            return created(save("topupBalance", "TopupBalance", "done", request, objectMapper.createObjectNode()));
        }
        double amount = number(request.path("amount").get("amount"));
        if (amount <= 0) {
            throw new BadRequestException("amount {amount, units} must be positive");
        }
        String bucketId = request.path("bucket").hasNonNull("id") ? request.path("bucket").get("id").asText() : null;
        // the party boundary IS the authorization: only own subscribers reachable
        String tenant = tenantScope.currentTenantId();
        for (OcsSubscriber sub : ocs.subscribersOf(tenant, party)) {
            boolean match = bucketId == null || sub.hasBucket(bucketId);
            if (match && ocs.credit(tenant, sub.id(), amount)) {
                ObjectNode extra = objectMapper.createObjectNode();
                extra.set("amount", objectMapper.valueToTree(new Amount(amount, "GB")));
                extra.set("relatedParty", objectMapper.valueToTree(List.of(RelatedPartyRef.customer(party))));
                return created(save("topupBalance", "TopupBalance", "done", request, extra));
            }
        }
        throw new BadRequestException("no charging subscriber found for this party"
                + (ocs.enabled(tenant) ? "" : " (no OCS configured for this tenant)"));
    }

    @GetMapping("/topupBalance")
    public ResponseEntity<List<?>> topups(
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String usageType) {
        return ResponseEntity.ok(select(list("topupBalance", id, status, usageType), fields));
    }

    @GetMapping("/topupBalance/{id}")
    public ResponseEntity<ObjectNode> topupById(@PathVariable("id") String id) {
        return ResponseEntity.ok(byId("topupBalance", "TopupBalance", id));
    }

    /* ---------- adjustBalance / reserveBalance: recorded, not charged ----------
     * The OCS owns adjustment and reservation truth (Gy/Ro land). The facade
     * records the TMF654 task so the API face is complete and auditable; a
     * production OCS behind the seam is where the counters actually move. */

    @PostMapping("/adjustBalance")
    public ResponseEntity<ObjectNode> adjust(@RequestBody ObjectNode request) {
        ObjectNode extra = objectMapper.createObjectNode();
        if (!request.hasNonNull("usageType")) {
            extra.put("usageType", "monetary");
        }
        return created(save("adjustBalance", "AdjustBalance", "done", request, extra));
    }

    @GetMapping("/adjustBalance")
    public ResponseEntity<List<?>> adjusts(
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String usageType) {
        return ResponseEntity.ok(select(list("adjustBalance", id, status, usageType), fields));
    }

    @GetMapping("/adjustBalance/{id}")
    public ResponseEntity<ObjectNode> adjustById(@PathVariable("id") String id) {
        return ResponseEntity.ok(byId("adjustBalance", "AdjustBalance", id));
    }

    @PostMapping("/reserveBalance")
    public ResponseEntity<ObjectNode> reserve(@RequestBody ObjectNode request) {
        ObjectNode extra = objectMapper.createObjectNode();
        JsonNode parties = request.get("relatedParty");
        if (parties == null || !parties.isArray() || parties.isEmpty()) {
            partyScope.scopedPartyId().ifPresent(p ->
                    extra.set("relatedParty", objectMapper.valueToTree(List.of(RelatedPartyRef.customer(p)))));
        }
        return created(save("reserveBalance", "ReserveBalance", "done", request, extra));
    }

    @GetMapping("/reserveBalance")
    public ResponseEntity<List<?>> reserves(
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String usageType) {
        return ResponseEntity.ok(select(list("reserveBalance", id, status, usageType), fields));
    }

    @GetMapping("/reserveBalance/{id}")
    public ResponseEntity<ObjectNode> reserveById(@PathVariable("id") String id) {
        return ResponseEntity.ok(byId("reserveBalance", "ReserveBalance", id));
    }

    /* ---------- the task-resource plumbing ---------- */

    /** The caller's document with the server's keys laid over it, stored and answered as one. */
    private ObjectNode save(String type, String atType, String status, ObjectNode body, ObjectNode serverFields) {
        ObjectNode echo = body.deepCopy();
        echo.setAll(serverFields);
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
        task.setUsageType(echo.hasNonNull("usageType") ? echo.get("usageType").asText() : null);
        task.setPayloadJson(writeJson(echo));
        task.setCreatedAt(OffsetDateTime.now());
        tasks.save(task);
        return echo;
    }

    private List<ObjectNode> list(String type, String id, String status, String usageType) {
        List<ObjectNode> out = new ArrayList<>();
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

    private ObjectNode byId(String type, String resource, String id) {
        return tasks.findByIdAndTenantIdAndResourceType(id, tenantScope.currentTenantId(), type)
                .map(t -> readJson(t.getPayloadJson()))
                .orElseThrow(() -> NotFoundException.forResource(resource, id));
    }

    private List<?> select(List<?> items, String fields) {
        return fields == null ? items : fieldSelector.select(items, fields);
    }

    private ResponseEntity<ObjectNode> created(ObjectNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ObjectNode readJson(String s) {
        try {
            JsonNode node = s == null ? null : objectMapper.readTree(s);
            return node instanceof ObjectNode object ? object : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static double number(JsonNode value) {
        try {
            return value == null || value.isNull() ? 0 : Double.parseDouble(value.asText());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
