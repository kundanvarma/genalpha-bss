package com.bss.som.repository;

import com.bss.som.entity.ServiceTestSpec;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceTestSpecRepository extends JpaRepository<ServiceTestSpec, String> {

    List<ServiceTestSpec> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    Optional<ServiceTestSpec> findByIdAndTenantId(String id, String tenantId);
}
