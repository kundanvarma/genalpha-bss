package com.bss.som.repository;

import com.bss.som.entity.ServiceTest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceTestRepository extends JpaRepository<ServiceTest, String> {
    Optional<ServiceTest> findByIdAndTenantId(String id, String tenantId);
    List<ServiceTest> findTop50ByTenantIdAndServiceIdOrderByCreatedAtDesc(String tenantId, String serviceId);
    List<ServiceTest> findTop50ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
