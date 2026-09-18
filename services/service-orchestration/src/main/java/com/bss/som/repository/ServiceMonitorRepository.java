package com.bss.som.repository;

import com.bss.som.entity.ServiceMonitor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceMonitorRepository extends JpaRepository<ServiceMonitor, String> {
    Optional<ServiceMonitor> findByIdAndTenantId(String id, String tenantId);
    List<ServiceMonitor> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<ServiceMonitor> findFirstByTenantIdAndServiceIdOrderByCreatedAtDesc(String tenantId, String serviceId);
}
