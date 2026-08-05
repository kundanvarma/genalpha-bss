package com.bss.som.repository;

import com.bss.som.entity.InventoryResource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryResourceRepository extends JpaRepository<InventoryResource, String> {

    List<InventoryResource> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<InventoryResource> findByTenantIdAndName(String tenantId, String name);

    Optional<InventoryResource> findByIdAndTenantId(String id, String tenantId);
}
