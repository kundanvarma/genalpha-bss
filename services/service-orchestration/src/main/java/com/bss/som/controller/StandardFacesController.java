package com.bss.som.controller;

import com.bss.som.entity.NumberQuarantine;
import com.bss.som.entity.ResourceAssignment;
import com.bss.som.entity.ResourcePool;
import com.bss.som.entity.ServiceTest;
import com.bss.som.repository.NumberQuarantineRepository;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ResourcePoolRepository;
import com.bss.som.repository.ServiceTestRepository;
import com.bss.som.security.PartyScope;
import com.bss.som.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Two thin standard faces over what SOM already owns.
 * TMF653: the diagnose triage becomes a serviceTest WITH HISTORY — the
 * test executes the same code path the CSR button uses (the sibling
 * controller is invoked directly: same security context, same owner
 * check, zero duplicated logic). TMF639: the pools and the issued-number
 * ledger, HONESTLY labeled — a pool here is a monotonic counter, so the
 * face reports what was ISSUED and quarantined and never invents an
 * "available" count.
 */
@RestController
public class StandardFacesController {

    private final SomController som;
    private final ServiceTestRepository tests;
    private final ResourcePoolRepository pools;
    private final ResourceAssignmentRepository assignments;
    private final NumberQuarantineRepository quarantine;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StandardFacesController(SomController som, ServiceTestRepository tests,
            ResourcePoolRepository pools, ResourceAssignmentRepository assignments,
            NumberQuarantineRepository quarantine, TenantScope tenantScope, PartyScope partyScope) {
        this.som = som;
        this.tests = tests;
        this.pools = pools;
        this.assignments = assignments;
        this.quarantine = quarantine;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
    }

    /* ---------- TMF653 serviceTest ---------- */

    @PostMapping("/tmf-api/serviceTestManagement/v4/serviceTest")
    public ResponseEntity<Map<String, Object>> runTest(@RequestBody Map<String, Object> dto) {
        String serviceId = dto.get("relatedService") instanceof Map<?, ?> ref
                && ref.get("id") != null ? String.valueOf(ref.get("id")) : null;
        if (serviceId == null) {
            throw new com.bss.som.exception.BadRequestException("relatedService {id} is required");
        }
        // same code path, same owner check as the CSR Diagnose button
        Map<String, Object> diagnosis = som.diagnose(serviceId).getBody();
        ServiceTest test = new ServiceTest();
        test.setId(UUID.randomUUID().toString());
        test.setTenantId(tenantScope.currentTenantId());
        test.setServiceId(serviceId);
        test.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
        test.setVerdict(String.valueOf(diagnosis.get("verdict")));
        test.setFindingsJson(writeJson(diagnosis.get("findings")));
        test.setCreatedAt(OffsetDateTime.now());
        tests.save(test);
        return ResponseEntity.status(HttpStatus.CREATED).body(testView(test));
    }

    @GetMapping("/tmf-api/serviceTestManagement/v4/serviceTest")
    public ResponseEntity<List<Map<String, Object>>> listTests(
            @RequestParam(required = false) String serviceId) {
        String tenant = tenantScope.currentTenantId();
        List<ServiceTest> found = serviceId != null
                ? tests.findTop50ByTenantIdAndServiceIdOrderByCreatedAtDesc(tenant, serviceId)
                : tests.findTop50ByTenantIdOrderByCreatedAtDesc(tenant);
        return ResponseEntity.ok(found.stream()
                .filter(t -> partyScope.scopedPartyId()
                        .map(own -> own.equals(t.getOwnerPartyId())).orElse(true))
                .map(this::testView).toList());
    }

    @GetMapping("/tmf-api/serviceTestManagement/v4/serviceTest/{id}")
    public ResponseEntity<Map<String, Object>> testById(@PathVariable("id") String id) {
        ServiceTest t = tests.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> com.bss.som.exception.NotFoundException
                        .forResource("ServiceTest", id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(t.getOwnerPartyId())) {
                throw com.bss.som.exception.NotFoundException.forResource("ServiceTest", id);
            }
        });
        return ResponseEntity.ok(testView(t));
    }

    /* ---------- TMF639 resource faces (staff-grade reads) ---------- */

    @GetMapping("/tmf-api/resourceInventoryManagement/v4/resourcePool")
    public ResponseEntity<List<Map<String, Object>>> resourcePools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ResourcePool pool : pools.findByTenantId(tenantScope.currentTenantId())) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", pool.getId());
            map.put("name", pool.getName());
            map.put("resourceType", pool.getResourceType());
            map.put("prefix", pool.getPrefix());
            map.put("issuedCounter", pool.getNextValue());
            map.put("note", "this pool is a generator, not a free-list — issued and "
                    + "quarantined are facts; an 'available' count would be an invention");
            map.put("@type", "ResourcePool");
            out.add(map);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/tmf-api/resourceInventoryManagement/v4/resource")
    public ResponseEntity<List<Map<String, Object>>> resources(
            @RequestParam(required = false) String serviceId) {
        String tenant = tenantScope.currentTenantId();
        List<Map<String, Object>> out = new ArrayList<>();
        List<ResourceAssignment> issued = serviceId != null
                ? assignments.findByTenantIdAndServiceId(tenant, serviceId)
                : assignments.findAll().stream()
                        .filter(a -> tenant.equals(a.getTenantId())).limit(200).toList();
        for (ResourceAssignment a : issued) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", a.getId());
            map.put("value", a.getValue());
            map.put("resourceStatus", "assigned");
            map.put("poolId", a.getPoolId());
            if (a.getServiceId() != null) {
                map.put("relatedService", Map.of("id", a.getServiceId()));
            }
            if (a.getOwnerPartyId() != null) {
                map.put("relatedParty", List.of(Map.of("id", a.getOwnerPartyId(), "role", "customer")));
            }
            map.put("@type", "Resource");
            out.add(map);
        }
        for (NumberQuarantine q : quarantine.findAll().stream()
                .filter(q -> tenant.equals(q.getTenantId())).limit(100).toList()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", "quarantine-" + q.getNumber());
            map.put("value", q.getNumber());
            map.put("resourceStatus", "quarantined");
            map.put("@type", "Resource");
            out.add(map);
        }
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> testView(ServiceTest t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", t.getId());
        map.put("href", "/tmf-api/serviceTestManagement/v4/serviceTest/" + t.getId());
        map.put("relatedService", Map.of("id", t.getServiceId()));
        map.put("state", "completed");
        map.put("verdict", t.getVerdict());
        try {
            map.put("testMeasure", t.getFindingsJson() == null ? List.of()
                    : objectMapper.readValue(t.getFindingsJson(), List.class));
        } catch (Exception e) {
            map.put("testMeasure", List.of());
        }
        map.put("createdAt", t.getCreatedAt());
        map.put("@type", "ServiceTest");
        return map;
    }

    private String writeJson(Object o) {
        try {
            return o == null ? null : objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
