package com.bss.som.repository;

import com.bss.som.entity.ResourceAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResourceAssignmentRepository extends JpaRepository<ResourceAssignment, String> {

    List<ResourceAssignment> findByTenantIdAndServiceId(String tenantId, String serviceId);

    /** Who holds this number — gifting resolves a typed MSISDN to its owner. */
    java.util.Optional<ResourceAssignment> findFirstByTenantIdAndValue(String tenantId, String value);
}
