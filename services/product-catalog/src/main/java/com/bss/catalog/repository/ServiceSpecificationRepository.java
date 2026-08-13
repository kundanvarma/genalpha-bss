package com.bss.catalog.repository;

import com.bss.catalog.entity.ServiceSpecification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface ServiceSpecificationRepository extends JpaRepository<ServiceSpecification, String> {

    Optional<ServiceSpecification> findByIdAndTenantId(String id, String tenantId);
}
