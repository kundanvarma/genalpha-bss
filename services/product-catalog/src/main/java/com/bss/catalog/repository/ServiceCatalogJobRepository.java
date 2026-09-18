package com.bss.catalog.repository;

import com.bss.catalog.entity.ServiceCatalogJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ServiceCatalogJobRepository extends JpaRepository<ServiceCatalogJob, String> {

    Optional<ServiceCatalogJob> findByIdAndTenantIdAndKind(String id, String tenantId, String kind);
}
