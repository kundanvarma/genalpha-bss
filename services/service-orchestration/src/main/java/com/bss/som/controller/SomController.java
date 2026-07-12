package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.entity.ServiceOrder;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.entity.ResourcePool;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ResourcePoolRepository;
import com.bss.som.repository.ServiceOrderRepository;
import com.bss.som.security.PartyScope;
import com.bss.som.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-side TMF641/638: what the production layer did and what is running. */
@RestController
public class SomController {

    private final ServiceOrderRepository serviceOrders;
    private final ServiceInstanceRepository services;
    private final ResourcePoolRepository pools;
    private final ResourceAssignmentRepository assignments;
    private final TenantScope tenantScope;
    private final PartyScope partyScope;
    private final com.bss.som.events.DomainEventPublisher events;
    private final com.bss.som.service.OrchestrationService orchestration;
    private final com.bss.som.repository.SimCardRepository sims;
    private final com.bss.som.client.SimPlatformClient simPlatform;

    public SomController(ServiceOrderRepository serviceOrders, ServiceInstanceRepository services,
            ResourcePoolRepository pools, ResourceAssignmentRepository assignments,
            TenantScope tenantScope, PartyScope partyScope,
            com.bss.som.events.DomainEventPublisher events,
            com.bss.som.service.OrchestrationService orchestration,
            com.bss.som.repository.SimCardRepository sims,
            com.bss.som.client.SimPlatformClient simPlatform) {
        this.serviceOrders = serviceOrders;
        this.services = services;
        this.pools = pools;
        this.assignments = assignments;
        this.tenantScope = tenantScope;
        this.partyScope = partyScope;
        this.events = events;
        this.orchestration = orchestration;
        this.sims = sims;
        this.simPlatform = simPlatform;
    }

    /**
     * The SIM behind a numbered service: masked ICCID by default; the PUK
     * only with ?reveal=true. Owner-checked — a customer token addresses only
     * their own service, and a foreign id is a 404, never a 403.
     */
    @GetMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/sim")
    public ResponseEntity<Map<String, Object>> sim(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestParam(name = "reveal", defaultValue = "false") boolean reveal) {
        com.bss.som.entity.SimCard sim = requireOwnSim(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceId", id);
        body.put("iccid", "•••• " + sim.getIccid().substring(sim.getIccid().length() - 5));
        if (reveal) {
            body.put("puk", sim.getPuk());
        }
        body.put("@type", "SimCard");
        return ResponseEntity.ok(body);
    }

    /**
     * OTA PIN change through the SIM-platform seam. The PIN goes to the card,
     * never stored or logged in the BSS.
     */
    @PostMapping(ApiConstants.INVENTORY_BASE + "/service/{id}/sim/resetPin")
    public ResponseEntity<Map<String, Object>> resetPin(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestBody Map<String, Object> body) {
        com.bss.som.entity.SimCard sim = requireOwnSim(id);
        String pin = String.valueOf(body.getOrDefault("newPin", ""));
        if (!pin.matches("\\d{4,8}")) {
            throw new com.bss.som.exception.BadRequestException("newPin must be 4-8 digits");
        }
        if (!simPlatform.resetPin(sim.getIccid(), pin)) {
            throw new com.bss.som.exception.BadRequestException("the SIM platform refused the PIN change");
        }
        events.publish("SimPinResetEvent", "sim", Map.of(
                "serviceId", id,
                "iccid", "•••• " + sim.getIccid().substring(sim.getIccid().length() - 5)));
        return ResponseEntity.ok(Map.of("status", "done", "@type", "SimPinReset"));
    }

    private com.bss.som.entity.SimCard requireOwnSim(String serviceId) {
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findByIdAndTenantId(serviceId, tenant)
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("Service", serviceId));
        partyScope.scopedPartyId().ifPresent(own -> {
            if (!own.equals(instance.getOwnerPartyId())) {
                throw com.bss.som.exception.NotFoundException.forResource("Service", serviceId);
            }
        });
        return sims.findFirstByTenantIdAndServiceId(tenant, serviceId)
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("SIM for service", serviceId));
    }

    @GetMapping(ApiConstants.ORDER_BASE + "/serviceOrder")
    public ResponseEntity<List<Map<String, Object>>> serviceOrders(
            @RequestParam(required = false) String productOrderId) {
        String tenant = tenantScope.currentTenantId();
        List<ServiceOrder> rows = productOrderId != null
                ? serviceOrders.findByTenantIdAndProductOrderId(tenant, productOrderId)
                : serviceOrders.findAll().stream()
                        .filter(o -> tenant.equals(o.getTenantId())).toList();
        return ResponseEntity.ok(rows.stream().map(this::orderMap).toList());
    }

    @GetMapping(ApiConstants.INVENTORY_BASE + "/service")
    public ResponseEntity<List<Map<String, Object>>> services(
            @RequestParam(name = "relatedPartyId", required = false) String relatedPartyId,
            @RequestParam(name = "deliveryPath", required = false) String deliveryPath) {
        String tenant = tenantScope.currentTenantId();
        // Customers see their own running services; staff filter freely.
        String party = partyScope.scopedPartyId().orElse(relatedPartyId);
        List<ServiceInstance> rows = deliveryPath != null
                ? services.findByTenantIdAndDeliveryPath(tenant, deliveryPath)
                : party != null
                        ? services.findByTenantIdAndOwnerPartyId(tenant, party)
                        : services.findAll().stream()
                                .filter(s -> tenant.equals(s.getTenantId())).toList();
        return ResponseEntity.ok(rows.stream().map(this::serviceMap).toList());
    }

    /**
     * The self-healing hook: re-home a service to a new delivery point.
     * Assurance calls this when the current path fails — fibre cut, edge
     * takes over, SLA restored. Machine or staff only (service:write).
     */
    @org.springframework.web.bind.annotation.PostMapping(
            ApiConstants.INVENTORY_BASE + "/service/{id}/migrate")
    public ResponseEntity<Map<String, Object>> migrate(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        String tenant = tenantScope.currentTenantId();
        ServiceInstance instance = services.findById(id)
                .filter(s -> tenant.equals(s.getTenantId()))
                .orElseThrow(() -> com.bss.som.exception.NotFoundException.forResource("Service", id));
        String from = instance.getDeliveryPath();
        instance.setDeliveryPath(String.valueOf(body.get("deliveryPoint")));
        instance.setLastUpdate(java.time.OffsetDateTime.now());
        services.save(instance);
        events.publish("ServiceAttributeValueChangeEvent", "service", Map.of(
                "id", instance.getId(), "name", instance.getName(),
                "deliveryPath", instance.getDeliveryPath(),
                "previousDeliveryPath", from == null ? "" : from,
                "relatedParty", instance.getOwnerPartyId() == null ? List.of()
                        : List.of(Map.of("id", instance.getOwnerPartyId(), "role", "customer"))));
        return ResponseEntity.ok(serviceMap(instance));
    }

    /** Cease a service (disconnect) — staff/machine; releases the number. */
    @org.springframework.web.bind.annotation.PostMapping(
            ApiConstants.INVENTORY_BASE + "/service/{id}/terminate")
    public ResponseEntity<Map<String, Object>> terminate(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @org.springframework.web.bind.annotation.RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null || body.get("reason") == null ? "cease" : String.valueOf(body.get("reason"));
        return ResponseEntity.ok(orchestration.terminateService(id, reason));
    }

    private Map<String, Object> orderMap(ServiceOrder o) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("href", o.getHref());
        map.put("state", o.getState());
        map.put("category", o.getItemName());
        map.put("productOrderId", o.getProductOrderId());
        if (o.getCompletedAt() != null) map.put("completionDate", o.getCompletedAt().toString());
        map.put("@type", "ServiceOrder");
        return map;
    }

    @PostMapping("/tmf-api/resourcePoolManagement/v4/resourcePool")
    public ResponseEntity<Map<String, Object>> createPool(@RequestBody Map<String, Object> dto) {
        ResourcePool pool = new ResourcePool();
        pool.setId(java.util.UUID.randomUUID().toString());
        pool.setTenantId(tenantScope.currentTenantId());
        pool.setHref("/tmf-api/resourcePoolManagement/v4/resourcePool/" + pool.getId());
        pool.setName(String.valueOf(dto.getOrDefault("name", "numbers")));
        pool.setResourceType(String.valueOf(dto.getOrDefault("resourceType", ResourcePool.MSISDN)));
        pool.setPrefix(String.valueOf(dto.get("prefix")));
        pool.setNextValue(dto.get("nextValue") instanceof Number n ? n.longValue() : 1L);
        pool.setCreatedAt(java.time.OffsetDateTime.now());
        pool.setLastUpdate(java.time.OffsetDateTime.now());
        pools.save(pool);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", pool.getId());
        map.put("name", pool.getName());
        map.put("resourceType", pool.getResourceType());
        map.put("prefix", pool.getPrefix());
        map.put("@type", "ResourcePool");
        return ResponseEntity.status(HttpStatus.CREATED).body(map);
    }

    @GetMapping("/tmf-api/resourcePoolManagement/v4/resourcePool")
    public ResponseEntity<List<Map<String, Object>>> listPools() {
        return ResponseEntity.ok(pools.findByTenantId(tenantScope.currentTenantId()).stream()
                .map(p -> {
                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                    map.put("id", p.getId());
                    map.put("name", p.getName());
                    map.put("resourceType", p.getResourceType());
                    map.put("prefix", p.getPrefix());
                    map.put("@type", "ResourcePool");
                    return map;
                }).toList());
    }

    private Map<String, Object> serviceMap(ServiceInstance s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", s.getId());
        map.put("href", s.getHref());
        map.put("name", s.getName());
        map.put("state", s.getState());
        map.put("serviceOrderId", s.getServiceOrderId());
        if (s.getOwnerPartyId() != null) {
            map.put("relatedParty", List.of(Map.of("id", s.getOwnerPartyId(), "role", "customer")));
        }
        List<Map<String, Object>> supporting = assignments
                .findByTenantIdAndServiceId(s.getTenantId(), s.getId()).stream()
                .map(a -> Map.<String, Object>of("value", a.getValue(), "@referredType", "Resource"))
                .toList();
        if (s.getDeliveryPath() != null) {
            map.put("deliveryPath", s.getDeliveryPath());
        }
        if (!supporting.isEmpty()) {
            map.put("supportingResource", supporting);
        }
        map.put("@type", "Service");
        return map;
    }
}
