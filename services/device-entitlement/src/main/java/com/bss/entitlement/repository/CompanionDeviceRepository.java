package com.bss.entitlement.repository;

import com.bss.entitlement.entity.CompanionDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanionDeviceRepository extends JpaRepository<CompanionDevice, String> {

    Optional<CompanionDevice> findByTenantIdAndImsiAndCompanionTerminalId(String tenantId, String imsi, String companionTerminalId);

    List<CompanionDevice> findByTenantIdAndImsi(String tenantId, String imsi);

    List<CompanionDevice> findTop200ByTenantIdOrderByLastUpdateDesc(String tenantId);
}
