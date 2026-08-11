package com.bss.payment.repository;

import com.bss.payment.entity.PspConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PspConfigRepository extends JpaRepository<PspConfig, String> {

    List<PspConfig> findByTenantIdOrderByDisplayNameAsc(String tenantId);

    Optional<PspConfig> findByTenantIdAndProvider(String tenantId, String provider);

    List<PspConfig> findByTenantIdAndEnabledTrue(String tenantId);
}
