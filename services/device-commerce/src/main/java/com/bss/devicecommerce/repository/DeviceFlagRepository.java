package com.bss.devicecommerce.repository;

import com.bss.devicecommerce.entity.DeviceFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceFlagRepository extends JpaRepository<DeviceFlag, String> {

    List<DeviceFlag> findByTenantIdAndImei(String tenantId, String imei);

    List<DeviceFlag> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    boolean existsByTenantIdAndImeiAndFlag(String tenantId, String imei, String flag);
}
