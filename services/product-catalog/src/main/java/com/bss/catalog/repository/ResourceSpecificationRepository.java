package com.bss.catalog.repository;

import com.bss.catalog.entity.ResourceSpecification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ResourceSpecificationRepository extends JpaRepository<ResourceSpecification, String> {

    Optional<ResourceSpecification> findByIdAndTenantId(String id, String tenantId);
}
