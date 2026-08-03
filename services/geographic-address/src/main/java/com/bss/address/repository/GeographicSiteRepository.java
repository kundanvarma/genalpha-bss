package com.bss.address.repository;

import com.bss.address.entity.GeographicSite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GeographicSiteRepository extends JpaRepository<GeographicSite, String> {

    List<GeographicSite> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<GeographicSite> findByIdAndTenantId(String id, String tenantId);
}
