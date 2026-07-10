package com.bss.qualification.repository;

import com.bss.qualification.entity.ServiceableArea;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceableAreaRepository extends JpaRepository<ServiceableArea, String> {

    Optional<ServiceableArea> findByIdAndTenantId(String id, String tenantId);

    List<ServiceableArea> findByTenantIdAndProductOfferingId(String tenantId, String productOfferingId);
}
