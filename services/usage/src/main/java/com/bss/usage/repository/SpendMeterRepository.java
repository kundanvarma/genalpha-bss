package com.bss.usage.repository;

import com.bss.usage.entity.SpendMeter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpendMeterRepository extends JpaRepository<SpendMeter, String> {

    Optional<SpendMeter> findByTenantIdAndPartyIdAndMeterType(String tenantId, String partyId, String meterType);

    List<SpendMeter> findByTenantIdAndPartyId(String tenantId, String partyId);
}
