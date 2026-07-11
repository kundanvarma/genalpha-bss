package com.bss.address.repository;

import com.bss.address.entity.GeographicAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GeographicAddressRepository extends JpaRepository<GeographicAddress, String> {

    Optional<GeographicAddress> findByIdAndTenantId(String id, String tenantId);
}
