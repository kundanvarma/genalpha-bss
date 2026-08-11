package com.bss.fulfilment.repository;

import com.bss.fulfilment.entity.CarrierConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CarrierConfigRepository extends JpaRepository<CarrierConfig, String> {

    List<CarrierConfig> findByTenantIdOrderByDisplayNameAsc(String tenantId);

    Optional<CarrierConfig> findByTenantIdAndCarrier(String tenantId, String carrier);

    List<CarrierConfig> findByTenantIdAndEnabledTrue(String tenantId);
}
