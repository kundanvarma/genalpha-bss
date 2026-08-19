package com.bss.insight.repository;

import com.bss.insight.entity.SignalConnector;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SignalConnectorRepository extends JpaRepository<SignalConnector, String> {

    List<SignalConnector> findByTenantIdOrderByNameAsc(String tenantId);

    Optional<SignalConnector> findByTenantIdAndName(String tenantId, String name);

    Optional<SignalConnector> findByIdAndTenantId(String id, String tenantId);
}
