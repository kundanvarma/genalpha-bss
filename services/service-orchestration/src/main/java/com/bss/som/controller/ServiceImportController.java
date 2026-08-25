package com.bss.som.controller;

import com.bss.som.api.ApiConstants;
import com.bss.som.entity.ResourceAssignment;
import com.bss.som.entity.ResourcePool;
import com.bss.som.entity.ServiceInstance;
import com.bss.som.repository.ResourceAssignmentRepository;
import com.bss.som.repository.ServiceInstanceRepository;
import com.bss.som.security.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * B-M1 — the MIGRATION seam: a customer arriving from a legacy BSS brings a
 * number that already exists. This registers it as data — an ACTIVE service
 * instance plus the MSISDN as an assigned resource, no pool draw, no order —
 * idempotent per (owner, msisdn). The portal's "your number" reads exactly
 * these rows; day one on the new BSS looks like every other day.
 */
@RestController
@RequestMapping("/som/v1/importService")
public class ServiceImportController {

    private final ServiceInstanceRepository services;
    private final ResourceAssignmentRepository assignments;
    private final TenantScope tenantScope;

    public ServiceImportController(ServiceInstanceRepository services,
            ResourceAssignmentRepository assignments, TenantScope tenantScope) {
        this.services = services;
        this.assignments = assignments;
        this.tenantScope = tenantScope;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> importService(@RequestBody Map<String, Object> dto) {
        String tenant = tenantScope.currentTenantId();
        String owner = String.valueOf(dto.get("ownerPartyId"));
        String name = String.valueOf(dto.get("name"));
        String msisdn = dto.get("msisdn") == null ? null : String.valueOf(dto.get("msisdn"));
        if (owner == null || "null".equals(owner) || name == null || "null".equals(name)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ownerPartyId and name are required"));
        }
        if (msisdn != null) {
            boolean already = assignments.findAll().stream().anyMatch(a ->
                    tenant.equals(a.getTenantId()) && msisdn.equals(a.getValue())
                            && owner.equals(a.getOwnerPartyId()));
            if (already) {
                return ResponseEntity.ok(Map.of("imported", false, "reason", "already imported"));
            }
        }
        ServiceInstance instance = new ServiceInstance();
        String serviceId = UUID.randomUUID().toString();
        instance.setId(serviceId);
        instance.setTenantId(tenant);
        instance.setHref(ApiConstants.INVENTORY_BASE + "/service/" + serviceId);
        instance.setName(name);
        instance.setState(ServiceInstance.ACTIVE);
        instance.setServiceOrderId("imported");
        instance.setOwnerPartyId(owner);
        instance.setCreatedAt(OffsetDateTime.now());
        instance.setLastUpdate(OffsetDateTime.now());
        services.save(instance);
        if (msisdn != null) {
            ResourceAssignment a = new ResourceAssignment();
            a.setId(UUID.randomUUID().toString());
            a.setTenantId(tenant);
            a.setPoolId(ResourcePool.MSISDN);
            a.setValue(msisdn);
            a.setServiceId(serviceId);
            a.setOwnerPartyId(owner);
            a.setAssignedAt(OffsetDateTime.now());
            assignments.save(a);
        }
        return ResponseEntity.status(201).body(Map.of(
                "imported", true, "serviceId", serviceId,
                "name", name, "msisdn", msisdn == null ? "" : msisdn));
    }
}
