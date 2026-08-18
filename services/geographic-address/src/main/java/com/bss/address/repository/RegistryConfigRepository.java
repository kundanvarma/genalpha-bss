package com.bss.address.repository;

import com.bss.address.entity.RegistryConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegistryConfigRepository extends JpaRepository<RegistryConfig, String> {

    List<RegistryConfig> findByTenantIdOrderByCountryAsc(String tenantId);

    Optional<RegistryConfig> findByTenantIdAndCountry(String tenantId, String country);
}
