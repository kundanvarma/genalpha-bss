package com.bss.entitlement.repository;

import com.bss.entitlement.entity.EntitlementDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EntitlementDeviceRepository extends JpaRepository<EntitlementDevice, String> {

    Optional<EntitlementDevice> findByTenantIdAndTerminalId(String tenantId, String terminalId);

    List<EntitlementDevice> findByTenantIdAndImsi(String tenantId, String imsi);

    List<EntitlementDevice> findTop200ByTenantIdOrderByLastSeenAtDesc(String tenantId);
}
