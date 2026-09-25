package com.bss.som.controller;

import com.bss.som.api.Projection;
import com.bss.som.dto.LineReceipts.ServiceDiagnosis;
import com.bss.som.dto.ServiceRef;
import com.bss.som.dto.SpecRef;
import com.bss.som.dto.StandardFaceViews.NamedValue;
import com.bss.som.dto.StandardFaceViews.ResourcePoolView;
import com.bss.som.dto.StandardFaceViews.ResourceView;
import com.bss.som.dto.StandardFaceViews.ServiceSpecView;
import com.bss.som.dto.StandardFaceViews.ServiceTestRequest;
import com.bss.som.dto.StandardFaceViews.ServiceTestSpecRequest;
import com.bss.som.dto.StandardFaceViews.ServiceTestSpecView;
import com.bss.som.dto.StandardFaceViews.ServiceTestView;
import com.bss.som.entity.InventoryResource;
import com.bss.som.entity.NumberQuarantine;
import com.bss.som.entity.ResourceAssignment;
import com.bss.som.entity.ResourcePool;
import com.bss.som.entity.ServiceTest;
import com.bss.som.entity.ServiceTestSpec;
import com.bss.som.exception.BadRequestException;
import com.bss.som.exception.NotFoundException;
import com.bss.som.repository.InventoryResourceRepository;
import com.bss.som.repository.NumberQuarantineRepository;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ResourcePoolRepository;
import com.bss.som.repository.ServiceTestRepository;
import com.bss.som.repository.ServiceTestSpecRepository;
import com.bss.som.security.PartyScope;
import com.bss.som.security.TenantScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.UUID;

/**
 * Two thin standard faces over what SOM already owns.
 * TMF653: the diagnose triage becomes a serviceTest WITH HISTORY — the
 * test executes the same code path the CSR button uses (the sibling
 * controller is invoked directly: same security context, same owner
 * check, zero duplicated logic). TMF639: the pools and the issued-number
 * ledger, HONESTLY labeled — a pool here is a monotonic counter, so the
 * face reports what was ISSUED and quarantined and never invents an
 * "available" count. A resource a caller POSTed is kept verbatim and
 * answered as its own document with the row's facts on top.
 */
@RestController
public class StandardFacesController {

    private final SomController som;
    private final ServiceTestRepository tests;
    private final ServiceTestSpecRepository testSpecs;
    private final ResourcePoolRepository pools;
    private final ResourceAssignmentRepository assignments;
    private final NumberQuarantineRepository quarantine;
    private final InventoryResourceRepository inventory;
    private final com.bss.som.repository.ServiceRealisationRepository realisations;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final ObjectMapper objectMapper;

    public StandardFacesController(SomController som, ServiceTestRepository tests,
            ServiceTestSpecRepository testSpecs,
            ResourcePoolRepository pools, ResourceAssignmentRepository assignments,
            NumberQuarantineRepository quarantine, InventoryResourceRepository inventory,
            com.bss.som.repository.ServiceRealisationRepository realisations,
            TenantScope tenantScope, PartyScope partyScope, ObjectMapper objectMapper) {
        this.realisations = realisations;
        this.som = som;
        this.tests = tests;
        this.testSpecs = testSpecs;
        this.pools = pools;
        this.assignments = assignments;
        this.quarantine = quarantine;
        this.inventory = inventory;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.objectMapper = objectMapper;
    }

    /* ---------- TMF653 serviceTest ---------- */

    @PostMapping("/tmf-api/serviceTestManagement/v4/serviceTest")
    public ResponseEntity<ServiceTestView> runTest(@RequestBody ServiceTestRequest dto) {
        String serviceId = dto.serviceId();
        if (serviceId == null) {
            throw new BadRequestException("relatedService {id} is required");
        }
        if (dto.testSpecification() == null || !dto.testSpecification().isObject()
                || dto.testSpecification().get("id") == null || dto.testSpecification().get("id").isNull()) {
            throw new BadRequestException("testSpecification {id} is required — a test without a spec proves nothing");
        }
        ServiceTest test = new ServiceTest();
        test.setId(UUID.randomUUID().toString());
        test.setTenantId(tenantScope.currentTenantId());
        test.setServiceId(serviceId);
        test.setOwnerPartyId(partyScope.scopedPartyId().orElse(null));
        test.setName(dto.name() == null ? "diagnose " + serviceId : dto.name());
        test.setTestSpecJson(writeJson(dto.testSpecification()));
        try {
            // same code path, same owner check as the CSR Diagnose button
            ServiceDiagnosis diagnosis = som.diagnose(serviceId).getBody();
            test.setVerdict(diagnosis.verdict());
            test.setFindingsJson(writeJson(diagnosis.findings()));
        } catch (NotFoundException e) {
            // a party-scoped caller probing a foreign service keeps the 404
            // (the owner check IS the protection); STAFF referencing a
            // service outside this inventory gets an honest inconclusive
            if (partyScope.scopedPartyId().isPresent()) {
                throw e;
            }
            test.setVerdict("inconclusive");
            test.setFindingsJson(writeJson(List.of(new NamedValue("error",
                    "service '" + serviceId + "' is not in this inventory — "
                            + "reference recorded, nothing was measured"))));
        }
        test.setCreatedAt(OffsetDateTime.now());
        tests.save(test);
        return ResponseEntity.status(HttpStatus.CREATED).body(testView(test));
    }

    @GetMapping("/tmf-api/serviceTestManagement/v4/serviceTest")
    public ResponseEntity<List<ServiceTestView>> listTests(
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String name,
            @RequestParam(name = "relatedService.id", required = false) String relatedServiceId) {
        String tenant = tenantScope.currentTenantId();
        String svc = relatedServiceId != null ? relatedServiceId : serviceId;
        List<ServiceTest> found = svc != null
                ? tests.findTop50ByTenantIdAndServiceIdOrderByCreatedAtDesc(tenant, svc)
                : tests.findTop50ByTenantIdOrderByCreatedAtDesc(tenant);
        return ResponseEntity.ok(found.stream()
                .filter(t -> partyScope.scopedPartyId()
                        .map(own -> own.equals(t.getOwnerPartyId())).orElse(true))
                .map(this::testView)
                .filter(v -> name == null || name.equals(v.name()))
                .toList());
    }

    @GetMapping("/tmf-api/serviceTestManagement/v4/serviceTest/{id}")
    public ResponseEntity<JsonNode> testById(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        ServiceTest t = tests.findByIdAndTenantId(id, tenantScope.currentTenantId())
                .orElseThrow(() -> NotFoundException.forResource("ServiceTest", id));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(t.getOwnerPartyId())) {
                throw NotFoundException.forResource("ServiceTest", id);
            }
        });
        return ResponseEntity.ok(Projection.selectExact(objectMapper, testView(t), fields, "id"));
    }

    /* ---------- TMF653 serviceTestSpecification ---------- */

    @PostMapping("/tmf-api/serviceTestManagement/v4/serviceTestSpecification")
    public ResponseEntity<ServiceTestSpecView> createTestSpec(@RequestBody ServiceTestSpecRequest dto) {
        if (dto.name() == null || dto.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        JsonNode rel = dto.relatedServiceSpecification();
        if (rel == null || !rel.isObject() || rel.path("id").asText("").isBlank()) {
            throw new BadRequestException(
                    "relatedServiceSpecification {id} is required — a test spec tests SOMETHING");
        }
        ServiceTestSpec spec = new ServiceTestSpec();
        spec.setId(UUID.randomUUID().toString());
        spec.setTenantId(tenantScope.currentTenantId());
        spec.setName(dto.name());
        spec.setRelatedSpecJson(writeJson(rel));
        spec.setCreatedAt(OffsetDateTime.now());
        testSpecs.save(spec);
        return ResponseEntity.status(HttpStatus.CREATED).body(specTestView(spec));
    }

    @GetMapping("/tmf-api/serviceTestManagement/v4/serviceTestSpecification")
    public ResponseEntity<List<ServiceTestSpecView>> listTestSpecs(
            @RequestParam(required = false) String name,
            @RequestParam(name = "relatedServiceSpecification.id", required = false)
            String relatedSpecId) {
        List<ServiceTestSpecView> out = new ArrayList<>();
        out.add(diagnoseSpecView());
        for (ServiceTestSpec spec : testSpecs
                .findByTenantIdOrderByCreatedAtDesc(tenantScope.currentTenantId())) {
            out.add(specTestView(spec));
        }
        if (name != null) {
            out.removeIf(v -> !name.equals(v.name()));
        }
        if (relatedSpecId != null) {
            out.removeIf(v -> !(v.relatedServiceSpecification().isObject()
                    && relatedSpecId.equals(v.relatedServiceSpecification().path("id").asText())));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/tmf-api/serviceTestManagement/v4/serviceTestSpecification/{id}")
    public ResponseEntity<JsonNode> testSpecById(@PathVariable("id") String id,
            @RequestParam(required = false) String fields) {
        ServiceTestSpecView view = "diagnose".equals(id) ? diagnoseSpecView()
                : testSpecs.findByIdAndTenantId(id, tenantScope.currentTenantId())
                        .map(this::specTestView)
                        .orElseThrow(() -> NotFoundException.forResource("ServiceTestSpecification", id));
        return ResponseEntity.ok(Projection.selectExact(objectMapper, view, fields, "id"));
    }

    /** The built-in spec: the CSR diagnose triage, virtual for every tenant. */
    private ServiceTestSpecView diagnoseSpecView() {
        return new ServiceTestSpecView("diagnose",
                "/tmf-api/serviceTestManagement/v4/serviceTestSpecification/diagnose", "diagnose triage",
                objectMapper.valueToTree(new SpecRef("svcspec-service",
                        "/tmf-api/serviceCatalogManagement/v4/serviceSpecification/svcspec-service", null, null)),
                "ServiceTestSpecification");
    }

    private ServiceTestSpecView specTestView(ServiceTestSpec spec) {
        JsonNode related = readObject(spec.getRelatedSpecJson());
        return new ServiceTestSpecView(spec.getId(),
                "/tmf-api/serviceTestManagement/v4/serviceTestSpecification/" + spec.getId(), spec.getName(),
                related == null ? objectMapper.createObjectNode() : related, "ServiceTestSpecification");
    }

    /* ---------- TMF639 resource faces (staff-grade reads) ---------- */

    @GetMapping("/tmf-api/resourceInventoryManagement/v4/resourcePool")
    public ResponseEntity<List<ResourcePoolView>> resourcePools() {
        List<ResourcePoolView> out = new ArrayList<>();
        for (ResourcePool pool : pools.findByTenantId(tenantScope.currentTenantId())) {
            out.add(ResourcePoolView.facts(pool.getId(), pool.getName(), pool.getResourceType(), pool.getPrefix(),
                    pool.getNextValue()));
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/tmf-api/resourceInventoryManagement/v4/resource")
    public ResponseEntity<JsonNode> createResource(@RequestBody ObjectNode dto) {
        JsonNode name = dto.get("name");
        if (name == null || !name.isTextual() || name.asText().isBlank()) {
            throw new BadRequestException("name is required — an inventory record IS a named thing");
        }
        InventoryResource r = new InventoryResource();
        r.setId(UUID.randomUUID().toString());
        r.setTenantId(tenantScope.currentTenantId());
        r.setName(name.asText());
        r.setCategory(dto.get("category") != null && dto.get("category").isTextual()
                ? dto.get("category").asText() : null);
        r.setResourceStatus(dto.get("resourceStatus") != null && dto.get("resourceStatus").isTextual()
                ? dto.get("resourceStatus").asText() : "available");
        r.setDocumentJson(writeJson(dto));
        r.setCreatedAt(OffsetDateTime.now());
        inventory.save(r);
        ObjectNode view = storedView(r);
        return ResponseEntity.created(java.net.URI.create(view.get("href").asText())).body(view);
    }

    @GetMapping("/tmf-api/resourceInventoryManagement/v4/resource")
    public ResponseEntity<List<JsonNode>> resources(
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String name) {
        String tenant = tenantScope.currentTenantId();
        List<JsonNode> out = new ArrayList<>();
        for (InventoryResource r : inventory.findTop200ByTenantIdOrderByCreatedAtDesc(tenant)) {
            out.add(storedView(r));
        }
        List<ResourceAssignment> issued = serviceId != null
                ? assignments.findByTenantIdAndServiceId(tenant, serviceId)
                : assignments.findAll().stream()
                        .filter(a -> tenant.equals(a.getTenantId())).limit(200).toList();
        for (ResourceAssignment a : issued) {
            out.add(objectMapper.valueToTree(assignmentView(a)));
        }
        for (NumberQuarantine q : quarantine.findAll().stream()
                .filter(q -> tenant.equals(q.getTenantId())).limit(100).toList()) {
            out.add(objectMapper.valueToTree(ResourceView.quarantined(q.getNumber())));
        }
        if (serviceId != null) {
            out.removeIf(v -> !(v.path("relatedService").isObject()
                    && serviceId.equals(v.path("relatedService").path("id").asText(null))));
        }
        if (name != null) {
            out.removeIf(v -> !name.equals(v.path("name").asText(null)));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/tmf-api/resourceInventoryManagement/v4/resource/{id}")
    public ResponseEntity<JsonNode> resourceById(@PathVariable("id") String id) {
        String tenant = tenantScope.currentTenantId();
        return inventory.findByIdAndTenantId(id, tenant).map(r -> ResponseEntity.<JsonNode>ok(storedView(r)))
                .or(() -> assignments.findById(id)
                        .filter(a -> tenant.equals(a.getTenantId()))
                        .map(a -> ResponseEntity.<JsonNode>ok(objectMapper.valueToTree(assignmentView(a)))))
                .or(() -> quarantine.findAll().stream()
                        .filter(q -> tenant.equals(q.getTenantId())
                                && ("quarantine-" + q.getNumber()).equals(id))
                        .findFirst().map(q -> ResponseEntity.<JsonNode>ok(
                                objectMapper.valueToTree(ResourceView.quarantined(q.getNumber())))))
                .orElseThrow(() -> NotFoundException.forResource("Resource", id));
    }

    /** The caller's document as posted, with the row's facts written over it and a default @type. */
    private ObjectNode storedView(InventoryResource r) {
        ObjectNode map = objectMapper.createObjectNode();
        JsonNode doc = readObject(r.getDocumentJson());
        if (doc != null) {
            map.setAll((ObjectNode) doc);
        }
        map.put("id", r.getId());
        map.put("href", "/tmf-api/resourceInventoryManagement/v4/resource/" + r.getId());
        map.put("name", r.getName());
        if (r.getCategory() != null) {
            map.put("category", r.getCategory());
        }
        map.put("resourceStatus", r.getResourceStatus());
        if (!map.has("@type")) {
            map.put("@type", "Resource");
        }
        return map;
    }

    private ResourceView assignmentView(ResourceAssignment a) {
        ResourceView view = ResourceView.assigned(a.getId(), a.getValue(), a.getPoolId(), a.getServiceId(), a.getOwnerPartyId());
        if (a.getServiceId() == null) {
            return view;
        }
        // WHAT KIND of thing this issued resource is: the TMF634 spec named by the
        // RFS the orchestrator realised for its seam (a partner code or a number)
        String seam = "partner".equals(a.getPoolId()) ? "partner-entitlement" : "number";
        return realisations.findByTenantIdAndServiceIdOrderByRealisedAtAsc(a.getTenantId(), a.getServiceId()).stream()
                .filter(r -> seam.equals(r.getSeam()) && r.getResourceSpecId() != null)
                .findFirst()
                .map(r -> view.realising(r.getResourceSpecId(), r.getResourceSpecName()))
                .orElse(view);
    }

    /* ---------- TMF633 serviceSpecification (read-only, derived) ----------
     * The specs the inventory's serviceSpecification refs point at. Derived
     * from the categories services actually carry — a catalog face over
     * facts, not a modeling tool. */

    private static final List<String> SPEC_CATEGORIES = List.of("mobile", "broadband", "tv", "service");

    @GetMapping("/tmf-api/serviceCatalogManagement/v4/serviceSpecification")
    public ResponseEntity<List<ServiceSpecView>> serviceSpecifications() {
        return ResponseEntity.ok(SPEC_CATEGORIES.stream().map(ServiceSpecView::of).toList());
    }

    @GetMapping("/tmf-api/serviceCatalogManagement/v4/serviceSpecification/{id}")
    public ResponseEntity<ServiceSpecView> serviceSpecification(@PathVariable("id") String id) {
        return SPEC_CATEGORIES.stream()
                .filter(c -> ("svcspec-" + c).equals(id))
                .findFirst().map(c -> ResponseEntity.ok(ServiceSpecView.of(c)))
                .orElseThrow(() -> NotFoundException.forResource("ServiceSpecification", id));
    }

    private ServiceTestView testView(ServiceTest t) {
        JsonNode specRef = readObject(t.getTestSpecJson());
        JsonNode measures = readJson(t.getFindingsJson());
        return new ServiceTestView(t.getId(),
                "/tmf-api/serviceTestManagement/v4/serviceTest/" + t.getId(),
                t.getName() == null ? "diagnose " + t.getServiceId() : t.getName(),
                ServiceRef.inventory(t.getServiceId()),
                specRef != null ? specRef : objectMapper.valueToTree(SpecRef.diagnose()),
                "completed", t.getVerdict(),
                measures != null && measures.isArray() ? measures : objectMapper.createArrayNode(),
                t.getCreatedAt(), "ServiceTest");
    }

    private String writeJson(Object o) {
        try {
            return o == null ? null : objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readObject(String json) {
        JsonNode n = readJson(json);
        return n != null && n.isObject() ? n : null;
    }
}
