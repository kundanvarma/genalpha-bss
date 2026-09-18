package com.bss.som.repository;

import com.bss.som.entity.ServiceActivation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceActivationRepository extends JpaRepository<ServiceActivation, String> {
    Optional<ServiceActivation> findByServiceIdAndTenantId(String serviceId, String tenantId);
    List<ServiceActivation> findByTenantId(String tenantId);
}
